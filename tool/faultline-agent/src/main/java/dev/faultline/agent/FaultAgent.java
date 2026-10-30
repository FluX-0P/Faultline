package dev.faultline.agent;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * Loaded by the JVM before the app ({@code -javaagent:faultline-agent.jar}). Reads the plan that
 * {@code FAULTLINE_PLAN} points to and rewrites the matching methods as their classes load, so faults fire
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
        if (!plan.point().equals(BEFORE_ACK)) {
            log("unknown fault point '" + plan.point() + "', doing nothing");
            return;
        }
        if (remaining <= 0) {
            log("every planned crash already happened (" + plan.times() + " of " + plan.times() + "), doing nothing");
            return;
        }

        FaultTrigger.arm(plan);
        log("will crash at hit %d of %s (%d of %d crashes left)".formatted(plan.nthHit(), plan.point(), remaining, plan.times()));

        // keeps ByteBuddy off sun.misc.Unsafe, which newer JDKs warn about. Shading renames this property along with
        // the package, so it only affects our copy of ByteBuddy, never the app's own.
        System.setProperty("net.bytebuddy.safe", "true");

        new AgentBuilder.Default()
            // advice is copied into existing methods, so no new classes are ever injected (and no Unsafe is needed)
            .with(AgentBuilder.InjectionStrategy.Disabled.INSTANCE)
            .disableClassFormatChanges()
            // only look at Spring Kafka's own classes: fast startup, and nothing else in the app is touched
            .ignore(not(nameStartsWith("org.springframework.kafka.")))
            .type(hasSuperType(named(ACKNOWLEDGMENT)).and(not(isInterface())))
            .transform((builder, type, loader, module, domain) ->
                builder.visit(Advice.to(AckAdvice.class).on(named("acknowledge").and(takesNoArguments()))))
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded, DynamicType dynamicType) {
                    log("instrumented " + type.getName() + "#acknowledge");
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
