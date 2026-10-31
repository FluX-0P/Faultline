package dev.faultline.cli.llm;

import java.util.List;

/** A chat model asked to reply with JSON that follows a schema. Tests use a fake one, so they never need a key. */
public interface Llm {

    /** The reply text. The provider was asked to make it match {@code schema}, but callers still check it. */
    String reply(List<Message> messages, Schema schema);

    /** Part of the cache key: another model may give another answer. */
    String model();

    record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }
}
