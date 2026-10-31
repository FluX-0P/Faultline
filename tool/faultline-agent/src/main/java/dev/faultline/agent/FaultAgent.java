package dev.faultline.agent;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
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

    private FaultAgent() {}

    public static void premain(String args, Instrumentation inst) {
        String planFile = System.getenv(FaultPlan.ENV);
        if (planFile == null || planFile.isBlank()) {
            log("no " + FaultPlan.ENV + " set, doing nothing");
            return;
        }

        FaultPlan plan;
        int remaining;
        try {
            plan = FaultPlan.load(Path.of(planFile));
            remaining = plan.remaining();
        } catch (Exception e) {
            log("could not read plan " + planFile + " (" + e + "), doing nothing");
            return;
        }
        if (remaining <= 0) {
            log("every planned fault already fired (" + plan.times() + " of " + plan.times() + "), doing nothing");
            return;
        }

        FaultTrigger.arm(plan, remaining);
        log("will %s %s %s on every %dth call (%d of %d left)".formatted(plan.action(),
            plan.point().equals(BEFORE_ACK) ? "at" : plan.when(), plan.point(), plan.nthHit(), remaining, plan.times()));

        // keeps ByteBuddy off sun.misc.Unsafe, which newer JDKs warn about. Shading renames this property along with
        // the package, so it only affects our copy of ByteBuddy, never the app's own.
        System.setProperty("net.bytebuddy.safe", "true");

        if (plan.point().equals(BEFORE_ACK)) {
            // only Spring Kafka's own classes: fast startup, and nothing else in the app is touched
            install(inst, not(nameStartsWith("org.springframework.kafka.")),
                hasSuperType(named(ACKNOWLEDGMENT)).and(not(isInterface())),
                named("acknowledge").and(takesNoArguments()), BeforeAdvice.class);
        } else {
            String type = plan.typeName();
            // OrderService matches dev.example.OrderService; a Spring proxy (OrderService$$SpringCGLIB$$0) does not
            ElementMatcher.Junction<TypeDescription> types = type.contains(".") ? named(type) : named(type).or(nameEndsWith("." + type));
            install(inst, nameStartsWith("java.").or(nameStartsWith("jdk.")).or(nameStartsWith("sun.")).or(nameStartsWith("com.sun."))
                    .or(nameStartsWith("dev.faultline.agent.")),
                types.and(not(isInterface())), named(plan.methodName()).and(not(isAbstract())),
                plan.when().equals("after") ? AfterAdvice.class : BeforeAdvice.class);
        }
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
