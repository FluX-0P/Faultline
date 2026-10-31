package dev.faultline.agent;

import net.bytebuddy.asm.Advice;

/** Copied by ByteBuddy into the end of a barrier's until method: tells the barrier that someone got past it. */
public final class PassAdvice {
    private PassAdvice() {}

    @Advice.OnMethodExit
    static void exit() {
        Barrier.passed();
    }
}
