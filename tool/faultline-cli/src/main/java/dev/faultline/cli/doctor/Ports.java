package dev.faultline.cli.doctor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.faultline.cli.app.DockerStack;
import tools.jackson.databind.JsonNode;

/**
 * Ports on this machine: whether one is free, and which container holds one that is not. A taken port is reported,
 * never remapped: a run that quietly talked to another stack's database would check the wrong data.
 */
final class Ports {
    private static final int CONNECT_TIMEOUT_MILLIS = 200;
    private static final Pattern PROJECT = Pattern.compile("(?:^|,)com\\.docker\\.compose\\.project=([^,]*)");

    /** A running container, its Compose project (empty when it has none) and the host ports it publishes. */
    record Container(String name, String project, List<Integer> ports) {}

    private Ports() {}

    /** Whether nothing listens on the port and it can be bound, on any address. */
    static boolean isFree(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS);
            return false;
        } catch (IOException e) {
            // nothing answered: try to bind it
        }
        try (var server = new ServerSocket()) {
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The running containers and the host ports they publish, from {@code docker ps} as JSON (a Go template with a
     * quoted label name does not survive Windows' command-line quoting).
     */
    static List<Container> containers() throws Exception {
        var containers = new ArrayList<Container>();
        for (JsonNode n : DoctorCommand.jsonLines(DockerStack.docker(List.of("ps", "--format", "json")))) {
            var project = PROJECT.matcher(n.path("Labels").asString(""));
            containers.add(new Container(n.path("Names").asString(""), project.find() ? project.group(1) : "", published(n.path("Ports").asString(""))));
        }
        return containers;
    }

    /** The host ports of a {@code docker ps} Ports column, such as "0.0.0.0:5432->5432/tcp, [::]:5432->5432/tcp". */
    static List<Integer> published(String ports) {
        var found = new ArrayList<Integer>();
        var m = Pattern.compile(":(\\d+)(?:-(\\d+))?->").matcher(ports);
        while (m.find()) {
            int from = Integer.parseInt(m.group(1));
            int to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
            for (int p = from; p <= to; p++) {
                if (!found.contains(p)) found.add(p);
            }
        }
        return found;
    }

    static Optional<Container> holder(int port, List<Container> containers) {
        return containers.stream().filter(c -> c.ports().contains(port)).findFirst();
    }
}
