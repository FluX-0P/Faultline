package dev.faultline.agent;

import net.bytebuddy.asm.Advice;

/** Copied by ByteBuddy into the end of the fault point's method: runs once it has returned normally. */
public final class AfterAdvice {
    private AfterAdvice() {}

    @Advice.OnMethodExit
    static void exit() {
        FaultTrigger.hit();
    }
}
