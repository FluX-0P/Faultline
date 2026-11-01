package dev.faultline.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Copied by ByteBuddy into the start of KafkaProducer.send(record, callback): every send of the app goes through it. */
public final class RenameAdvice {
    private RenameAdvice() {}

    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object record) {
        record = Rename.of(record);
    }
}
