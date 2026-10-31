package dev.faultline.agent;

import net.bytebuddy.asm.Advice;

/** Copied by ByteBuddy into the start of the fault point's method. */
public final class BeforeAdvice {
    private BeforeAdvice() {}

    @Advice.OnMethodEnter
    static void enter() {
        FaultTrigger.hit();
    }
}
