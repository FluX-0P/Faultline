package dev.faultline.agent;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/**
 * Loaded by the JVM before the app ({@code -javaagent:faultline-agent.jar}). Reads the plan that
 * {@code FAULTLINE_PLAN} points to and rewrites the fault point's method as its class loads, so faults fire
 * inside the app with no change to its code.
 */
public final class FaultAgent {
    /** Just before Spring Kafka commits the offset: after the app's own DB commit, which is bug B1's window. */
    static final String BEFORE_ACK = "before-ack";
    static final String ACKNOWLEDGMENT = "org.springframework.kafka.support.Acknowledgment";
    static final String PRODUCER = "org.apache.kafka.clients.producer.KafkaProducer";

    private FaultAgent() {}

    public static void premain(String args, Instrumentation inst) {
        String planFile = System.getenv(FaultPlan.ENV);
        if (planFile == null || planFile.isBlank()) {
            log("no " + FaultPlan.ENV + " set, doing nothing");
            return;
        }

        FaultPlan plan;
        try {
            plan = FaultPlan.load(Path.of(planFile));
        } catch (Exception e) {
            log("could not read plan " + planFile + " (" + e + "), doing nothing");
            return;
        }

        // keeps ByteBuddy off sun.misc.Unsafe, which newer JDKs warn about. Shading renames this property along with
        // the package, so it only affects our copy of ByteBuddy, never the app's own.
        System.setProperty("net.bytebuddy.safe", "true");

        // the relay's topic stays renamed for as long as the app runs, also once every fault has fired
        if (!plan.renames().isEmpty()) {
            Rename.topics(plan.renames());
            install(inst, not(named(PRODUCER)), named(PRODUCER), named("send").and(takesArguments(2)), RenameAdvice.class);
            plan.renames().forEach((from, to) -> log("will send what the app sends to %s to %s, where Faultline's relay reads it".formatted(from, to)));
        }
        if (plan.action().equals(FaultPlan.RENAME)) return;

        int remaining;
        try {
            remaining = plan.action().equals("signal") ? 1 : plan.remaining();
        } catch (Exception e) {
            log("could not read the fault log " + plan.log() + " (" + e + "), doing nothing");
            return;
        }
        if (remaining <= 0) {
            log("every planned fault already fired (" + plan.times() + " of " + plan.times() + "), doing nothing");
            return;
        }

        try {
            if (plan.action().equals("signal")) {
                // a barrier's partner: never fires a fault of its own, only reports its passes to the holding worker
                Barrier.signalTo(plan.signalTo());
                log("will tell %s each time %s returns".formatted(plan.signalTo(), plan.point()));
                installMethod(inst, plan.point(), PassAdvice.class);
                return;
            }
            if (plan.action().equals("barrier") && plan.listen() > 0) Barrier.listen(plan.listen());
        } catch (Exception e) {
            log("could not open the barrier's socket (" + e + "), doing nothing");
            return;
        }

        FaultTrigger.arm(plan, remaining);
        log("will %s %s %s on every %dth call (%d of %d left)%s".formatted(plan.action(),
            plan.point().equals(BEFORE_ACK) ? "at" : plan.when(), plan.point(), plan.nthHit(), remaining, plan.times(),
            plan.action().equals("barrier") ? ", until another worker passes " + plan.until() : ""));

        if (plan.point().equals(BEFORE_ACK)) {
            // only Spring Kafka's own classes: fast startup, and nothing else in the app is touched
            install(inst, not(nameStartsWith("org.springframework.kafka.")),
                hasSuperType(named(ACKNOWLEDGMENT)).and(not(isInterface())),
                named("acknowledge").and(takesNoArguments()), BeforeAdvice.class);
        } else {
            installMethod(inst, plan.point(), plan.when().equals("after") ? AfterAdvice.class : BeforeAdvice.class);
        }
        // passes by this app's own threads release the barrier too
        if (plan.action().equals("barrier")) installMethod(inst, plan.until(), PassAdvice.class);
    }

    /** Copies {@code advice} into the app's {@code Class#method}. */
    private static void installMethod(Instrumentation inst, String classAndMethod, Class<?> advice) {
        String type = FaultPlan.typeOf(classAndMethod);
        // OrderService matches dev.example.OrderService; a Spring proxy (OrderService$$SpringCGLIB$$0) does not
        ElementMatcher.Junction<TypeDescription> types = type.contains(".") ? named(type) : named(type).or(nameEndsWith("." + type));
        install(inst, nameStartsWith("java.").or(nameStartsWith("jdk.")).or(nameStartsWith("sun.")).or(nameStartsWith("com.sun."))
                .or(nameStartsWith("dev.faultline.agent.")),
            types.and(not(isInterface())), named(FaultPlan.methodOf(classAndMethod)).and(not(isAbstract())), advice);
    }

    private static void install(Instrumentation inst, ElementMatcher<? super TypeDescription> ignore, ElementMatcher<? super TypeDescription> types,
                                ElementMatcher<? super MethodDescription> methods, Class<?> advice) {
        new AgentBuilder.Default()
            // advice is copied into existing methods, so no new classes are ever injected (and no Unsafe is needed)
            .with(AgentBuilder.InjectionStrategy.Disabled.INSTANCE)
            .disableClassFormatChanges()
            .ignore(ignore)
            .type(types)
            .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(advice).on(methods)))
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded, DynamicType dynamicType) {
                    log("instrumented " + type.getName());
                }

                @Override
                public void onError(String typeName, ClassLoader loader, JavaModule module, boolean loaded, Throwable error) {
                    log("could not instrument " + typeName + ": " + error);
                }
            })
            .installOn(inst);
    }

    static void log(String message) {
        System.err.println("faultline-agent: " + message);
    }
}
