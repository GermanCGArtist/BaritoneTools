package dev.ingametest;

/** One independent in-game check. {@code modId} is the addon it needs; without that addon it is skipped. */
record Scenario(String name, String modId, Body body) {

    @FunctionalInterface
    interface Body {
        void run(Bot bot) throws Exception;
    }
}
