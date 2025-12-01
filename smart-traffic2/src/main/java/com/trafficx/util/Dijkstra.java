package com.trafficx.util;

import java.util.*;

public class Dijkstra {

    public static class Result {
        public final List<Long> path;
        public final double cost;

        public Result(List<Long> path, double cost) {
            this.path = path;
            this.cost = cost;
        }
    }

    public static Result compute(Map<Long, Map<Long, Double>> graph, Long start, Long end) {

        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        PriorityQueue<Long> pq = new PriorityQueue<>(Comparator.comparing(dist::get));

        for (Long node : graph.keySet()) {
            dist.put(node, Double.MAX_VALUE);
        }

        dist.put(start, 0.0);
        pq.add(start);

        while (!pq.isEmpty()) {
            long current = pq.poll();

            if (current == end) break;

            for (Map.Entry<Long, Double> entry : graph.get(current).entrySet()) {
                long neighbor = entry.getKey();
                double weight = entry.getValue();

                double newDist = dist.get(current) + weight;

                if (newDist < dist.get(neighbor)) {
                    dist.put(neighbor, newDist);
                    prev.put(neighbor, current);
                    pq.add(neighbor);
                }
            }
        }

        List<Long> path = new ArrayList<>();
        Long step = end;

        while (step != null && prev.containsKey(step)) {
            path.add(step);
            step = prev.get(step);
        }

        if (step != null) path.add(step);

        Collections.reverse(path);

        return new Result(path, dist.get(end));
    }
}
