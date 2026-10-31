package dev.faultline.cli.understand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import dev.faultline.cli.source.Endpoint;

/**
 * What the LLM made of the app: app-map.json. Entry points, user journeys and data stores, each tied to the code
 * that runs it by a Class#method handler. {@code app} and {@code model} are filled in by Faultline, not the LLM.
 */
public record AppMap(String app, String model, String summary, List<Module> modules, List<EntryPoint> entryPoints,
                     List<DataStore> dataStores, List<OutsideCall> outsideCalls, List<Journey> journeys) {

    private static final Pattern KEBAB = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public record Module(String name, String purpose) {}

    public record EntryPoint(String kind, String name, String handler, String purpose) {
        public EntryPoint {
            handler = withoutPackage(handler);
        }
    }

    public record DataStore(String kind, String name, List<String> tables) {}

    public record OutsideCall(String name, String calledFrom, String purpose) {
        public OutsideCall {
            calledFrom = withoutPackage(calledFrom);
        }
    }

    public record Journey(String name, String description, List<Step> steps) {}

    public record Step(String handler, String does) {
        public Step {
            handler = withoutPackage(handler);
        }
    }

    /** dev.example.OrderService#place -> OrderService#place. LLMs sometimes add the package; the meaning is the same. */
    static String withoutPackage(String handler) {
        if (handler == null || !handler.contains("#")) return handler;
        String type = handler.substring(0, handler.indexOf('#'));
        return type.substring(type.lastIndexOf('.') + 1) + handler.substring(handler.indexOf('#'));
    }

    public AppMap withSource(String app, String model) {
        return new AppMap(app, model, summary, modules, entryPoints, dataStores, outsideCalls, journeys);
    }

    /**
     * What is wrong with this map, measured against the code: every endpoint in the code must be an entry point,
     * and every handler the map names must exist. An LLM that invents a method is caught here.
     */
    public List<String> problems(List<Endpoint> endpoints, Set<String> handlers) {
        var problems = new ArrayList<String>();
        if (summary == null || summary.isBlank()) problems.add("summary is empty");
        if (modules == null || entryPoints == null || dataStores == null || outsideCalls == null || journeys == null) {
            problems.add("modules, entryPoints, dataStores, outsideCalls and journeys must all be present");
            return problems;
        }

        var endpointHandlers = endpoints.stream().map(Endpoint::handler).toList();
        var mapped = new HashSet<String>();
        for (var e : entryPoints) {
            mapped.add(e.handler());
            if (!endpointHandlers.contains(e.handler())) {
                problems.add("entry point '%s' names handler '%s', which is not an endpoint in the code (endpoints: %s)"
                    .formatted(e.name(), e.handler(), String.join(", ", endpointHandlers)));
            }
        }
        for (var e : endpoints) {
            if (!mapped.contains(e.handler())) problems.add("no entry point for " + e.handler() + " (" + e.name() + ")");
        }

        if (journeys.isEmpty() && !endpoints.isEmpty()) problems.add("journeys is empty, but the app has entry points");
        var names = new HashSet<String>();
        for (var j : journeys) {
            if (j.name() == null || !KEBAB.matcher(j.name()).matches()) problems.add("journey name '" + j.name() + "' is not kebab-case");
            else if (!names.add(j.name())) problems.add("two journeys are named '" + j.name() + "'");
            if (j.steps() == null || j.steps().isEmpty()) {
                problems.add("journey '" + j.name() + "' has no steps");
                continue;
            }
            for (var s : j.steps()) {
                if (!handlers.contains(s.handler())) {
                    problems.add("journey '%s' has a step in '%s', which is not a method in the code".formatted(j.name(), s.handler()));
                }
            }
        }
        return problems;
    }
}
