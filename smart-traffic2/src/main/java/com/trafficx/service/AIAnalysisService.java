package com.trafficx.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.*;

@Service
public class AIAnalysisService {

    private final RestTemplate restTemplate;
    
    @Value("${gemini.api.key:}")
    private String geminiApiKey;
    
    // Store conversation history per session
    private Map<String, List<ChatMessage>> conversationHistory = new HashMap<>();

    public AIAnalysisService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Interactive chat with AI about traffic
     */
    public ChatResponse chat(String sessionId, String userMessage, TrafficContext context) {
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            return getSimpleChatResponse(userMessage, context);
        }

        try {
            // Get or create conversation history
            List<ChatMessage> history = conversationHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());
            
            // Build context-aware prompt
            String systemContext = buildSystemContext(context);
            
            // Add user message to history
            history.add(new ChatMessage("user", userMessage));
            
            // Keep only last 10 messages to manage token limits
            if (history.size() > 10) {
                history = new ArrayList<>(history.subList(history.size() - 10, history.size()));
                conversationHistory.put(sessionId, history);
            }
            
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=" + geminiApiKey;
            
            JSONObject requestBody = new JSONObject();
            JSONArray contents = new JSONArray();
            
            // Add system context as first message
            JSONObject systemMessage = new JSONObject();
            JSONArray systemParts = new JSONArray();
            JSONObject systemPart = new JSONObject();
            systemPart.put("text", systemContext);
            systemParts.put(systemPart);
            systemMessage.put("parts", systemParts);
            systemMessage.put("role", "user");
            contents.put(systemMessage);
            
            // Add conversation history
            for (ChatMessage msg : history) {
                JSONObject content = new JSONObject();
                JSONArray parts = new JSONArray();
                JSONObject part = new JSONObject();
                part.put("text", msg.getContent());
                parts.put(part);
                content.put("parts", parts);
                content.put("role", msg.getRole().equals("assistant") ? "model" : "user");
                contents.put(content);
            }
            
            requestBody.put("contents", contents);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, String.class
            );
            
            String aiResponse = parseAIResponse(response.getBody());
            
            // Add AI response to history
            history.add(new ChatMessage("assistant", aiResponse));
            
            return new ChatResponse(aiResponse, true);
        } catch (Exception e) {
            System.err.println("Error calling AI API: " + e.getMessage());
            return getSimpleChatResponse(userMessage, context);
        }
    }

    /**
     * Build context for AI about current traffic situation
     */
    private String buildSystemContext(TrafficContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a helpful traffic assistant for ").append(context.getCity()).append(". ");
        prompt.append("You help users navigate traffic, plan routes, and understand current road conditions. ");
        prompt.append("Be conversational, friendly, and provide actionable advice.\n\n");
        
        prompt.append("Current Traffic Situation in ").append(context.getCity()).append(":\n\n");
        
        if (context.getIncidents() != null && !context.getIncidents().isEmpty()) {
            prompt.append("Active Incidents (").append(context.getIncidents().size()).append("):\n");
            for (TrafficApiService.TrafficIncident incident : context.getIncidents()) {
                prompt.append("- %s on %s (Started: %s, Delay: %d min, Severity: %d/4)\n".formatted(
                        incident.getDescription(),
                        incident.getRoadName() != null ? incident.getRoadName() : "Unknown Road",
                        incident.getStartTime() != null ? incident.getStartTime() : "Unknown",
                        incident.getDelay(),
                        incident.getSeverity()));
            }
        } else {
            prompt.append("- No major incidents reported\n");
        }
        
        prompt.append("\nTraffic Flow:\n");
        if (context.getFlows() != null && !context.getFlows().isEmpty()) {
            int avgCongestion = (int) context.getFlows().stream()
                .mapToInt(TrafficApiService.TrafficFlow::getCongestion)
                .average()
                .orElse(0);
            int avgSpeed = (int) context.getFlows().stream()
                .mapToInt(TrafficApiService.TrafficFlow::getCurrentSpeed)
                .average()
                .orElse(0);
            
            prompt.append("- Average congestion: %d%%\n".formatted((int) avgCongestion));
            prompt.append("- Average speed: %d km/h\n".formatted((int) avgSpeed));
        } else {
            prompt.append("- Normal flow conditions\n");
        }
        
        prompt.append("\nWhen users ask questions, provide:\n");
        prompt.append("- Specific route recommendations\n");
        prompt.append("- Time estimates and best departure times\n");
        prompt.append("- Alternative routes to avoid congestion\n");
        prompt.append("- Real-time updates on incidents\n");
        prompt.append("- Tips for safe driving in current conditions\n");
        
        return prompt.toString();
    }

    /**
     * Analyze traffic data (legacy method for quick summaries)
     */
    public String analyzeTraffic(String city, List<TrafficApiService.TrafficIncident> incidents, 
                                 List<TrafficApiService.TrafficFlow> flows) {
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            return getSimpleAnalysis(city, incidents, flows);
        }

        try {
            String prompt = buildTrafficPrompt(city, incidents, flows);
            
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=" + geminiApiKey;
            
            JSONObject requestBody = new JSONObject();
            JSONArray contents = new JSONArray();
            JSONObject content = new JSONObject();
            JSONArray parts = new JSONArray();
            JSONObject part = new JSONObject();
            part.put("text", prompt);
            parts.put(part);
            content.put("parts", parts);
            contents.put(content);
            requestBody.put("contents", contents);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, String.class
            );
            
            return parseAIResponse(response.getBody());
        } catch (Exception e) {
            System.err.println("Error calling AI API: " + e.getMessage());
            return getSimpleAnalysis(city, incidents, flows);
        }
    }

    private String buildTrafficPrompt(String city, List<TrafficApiService.TrafficIncident> incidents,
                                       List<TrafficApiService.TrafficFlow> flows) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the current traffic situation in ").append(city).append(":\n\n");
        
        prompt.append("Traffic Incidents:\n");
        if (incidents.isEmpty()) {
            prompt.append("- No major incidents reported\n");
        } else {
            for (TrafficApiService.TrafficIncident incident : incidents) {
                prompt.append("- %s on %s (Started: %s, Delay: %d min, Severity: %d/4)\n".formatted(
                        incident.getDescription(),
                        incident.getRoadName() != null ? incident.getRoadName() : "Unknown Road",
                        incident.getStartTime() != null ? incident.getStartTime() : "Unknown",
                        incident.getDelay(),
                        incident.getSeverity()));
            }
        }
        
        prompt.append("\nTraffic Flow:\n");
        if (flows.isEmpty()) {
            prompt.append("- Normal flow conditions\n");
        } else {
            int avgCongestion = flows.stream()
                .mapToInt(TrafficApiService.TrafficFlow::getCongestion)
                .sum() / flows.size();
            int avgSpeed = flows.stream()
                .mapToInt(TrafficApiService.TrafficFlow::getCurrentSpeed)
                .sum() / flows.size();
            
            prompt.append("- Average congestion: %d%%\n".formatted(avgCongestion));
            prompt.append("- Average speed: %d km/h\n".formatted(avgSpeed));
        }
        
        prompt.append("\nProvide a traffic analysis including:\n");
        prompt.append("1. Overall traffic condition\n");
        prompt.append("2. Main problem areas with specific road names\n");
        prompt.append("3. Recommendations for drivers\n");
        prompt.append("4. Best times to travel\n");
        prompt.append("5. Alternative routes to consider\n");
        
        return prompt.toString();
    }

    private String parseAIResponse(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            if (json.has("candidates") && json.getJSONArray("candidates").length() > 0) {
                JSONObject candidate = json.getJSONArray("candidates").getJSONObject(0);
                if (candidate.has("content")) {
                    JSONObject content = candidate.getJSONObject("content");
                    if (content.has("parts") && content.getJSONArray("parts").length() > 0) {
                        JSONObject part = content.getJSONArray("parts").getJSONObject(0);
                        return part.getString("text");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing AI response: " + e.getMessage());
        }
        return "Unable to generate AI analysis.";
    }

    private ChatResponse getSimpleChatResponse(String userMessage, TrafficContext context) {
        String response = getSimpleAnalysis(context.getCity(), context.getIncidents(), context.getFlows());
        response += "\n\nI'm here to help! Feel free to ask specific questions about routes, traffic conditions, or the best time to travel.";
        return new ChatResponse(response, false);
    }

    private String getSimpleAnalysis(String city, List<TrafficApiService.TrafficIncident> incidents,
                                      List<TrafficApiService.TrafficFlow> flows) {
        StringBuilder analysis = new StringBuilder();
        
        if (flows.isEmpty()) {
            analysis.append("Traffic conditions in ").append(city).append(" appear normal. ");
        } else {
            int avgCongestion = flows.stream()
                .mapToInt(TrafficApiService.TrafficFlow::getCongestion)
                .sum() / flows.size();
            
            if (avgCongestion > 70) {
                analysis.append("Heavy traffic congestion detected in ").append(city).append(". ");
            } else if (avgCongestion > 40) {
                analysis.append("Moderate traffic levels in ").append(city).append(". ");
            } else {
                analysis.append("Light traffic conditions in ").append(city).append(". ");
            }
        }
        
        if (!incidents.isEmpty()) {
            analysis.append("There are ").append(incidents.size())
                .append(" active incident(s) affecting traffic flow. ");
            
            long severeIncidents = incidents.stream()
                .filter(i -> i.getSeverity() >= 3)
                .count();
            
            if (severeIncidents > 0) {
                analysis.append("Consider alternative routes to avoid major delays.");
            } else {
                analysis.append("Minor delays expected in some areas.");
            }
        } else {
            analysis.append("No major incidents reported. Roads are clear.");
        }
        
        return analysis.toString();
    }

    /**
     * Clear conversation history for a session
     */
    public void clearConversation(String sessionId) {
        conversationHistory.remove(sessionId);
    }

    // Helper classes
    public static class TrafficContext {
        private String city;
        private List<TrafficApiService.TrafficIncident> incidents;
        private List<TrafficApiService.TrafficFlow> flows;

        public TrafficContext(String city, List<TrafficApiService.TrafficIncident> incidents, 
                            List<TrafficApiService.TrafficFlow> flows) {
            this.city = city;
            this.incidents = incidents;
            this.flows = flows;
        }

        public String getCity() { return city; }
        public List<TrafficApiService.TrafficIncident> getIncidents() { return incidents; }
        public List<TrafficApiService.TrafficFlow> getFlows() { return flows; }
    }

    public static class ChatMessage {
        private String role;
        private String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }

    public static class ChatResponse {
        private String message;
        private boolean fromAI;

        public ChatResponse(String message, boolean fromAI) {
            this.message = message;
            this.fromAI = fromAI;
        }

        public String getMessage() { return message; }
        public boolean isFromAI() { return fromAI; }
    }
}