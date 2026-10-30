package dev.faultline.agent;

import net.bytebuddy.asm.Advice;

/** Copied by ByteBuddy into the start of every {@code Acknowledgment.acknowledge()} implementation. */
public final class AckAdvice {
    private AckAdvice() {}

    @Advice.OnMethodEnter
    static void enter() {
        FaultTrigger.hit(FaultAgent.BEFORE_ACK);
    }
}
