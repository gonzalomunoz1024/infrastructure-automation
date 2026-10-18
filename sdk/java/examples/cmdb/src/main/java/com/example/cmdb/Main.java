package com.example.cmdb;

import java.nio.file.Path;
import java.time.Clock;

import com.iaf.provider.sdk.Provider;
import com.iaf.provider.sdk.ProviderWorker;
import com.iaf.provider.sdk.Publisher;

/**
 * {@code run} (the default) serves the provider until the process is stopped;
 * {@code publish [file]} writes the provider file; {@code submit} sends it to the platform.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Provider provider = new Cmdb(Clock.systemUTC()).provider();
        String command = args.length == 0 ? "run" : args[0];
        switch (command) {
            case "run" -> {
                try (ProviderWorker worker = ProviderWorker.start(provider)) {
                    worker.awaitShutdown();
                }
            }
            case "publish" -> {
                Path file = Path.of(args.length > 1 ? args[1] : "providers/" + provider.owner() + "/" + provider.name() + ".yaml");
                System.out.println("Wrote " + Publisher.write(provider, file));
            }
            case "submit" -> {
                Publisher.Submission s = Publisher.submit(provider);
                System.out.println("Change request " + s.changeRequest() + " (" + s.status() + ") opened for " + provider.name() + " " + provider.version());
            }
            default -> {
                System.err.println("usage: cmdb-provider [run | publish [file] | submit]");
                System.exit(2);
            }
        }
    }
}
