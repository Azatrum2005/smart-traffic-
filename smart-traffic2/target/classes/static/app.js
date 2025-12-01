// Global variables
let map;
let currentCity = null;
let cities = [];
let incidentMarkers = [];
let congestionMarkers = [];
let routeLayer = null;
let startMarker = null;
let endMarker = null;
let searchMarker = null;
let sessionId = generateSessionId();
let currentIncidents = [];
let currentFlows = [];

// Map layers
const mapLayers = {
    streets: L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© OpenStreetMap contributors',
        maxZoom: 19
    }),
    satellite: L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
        attribution: '© Esri',
        maxZoom: 19
    }),
    hybrid: L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
        attribution: '© Esri',
        maxZoom: 19
    })
};

function generateSessionId() {
    return 'session_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
}

// Initialize map
function initMap() {
    map = L.map('map', {
        center: [40.7128, -74.0060],
        zoom: 12,
        layers: [mapLayers.streets]
    });

    const hybridLabels = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        opacity: 0.5
    });

    document.querySelectorAll('input[name="mapLayer"]').forEach(radio => {
        radio.addEventListener('change', (e) => {
            const layer = e.target.value;
            Object.values(mapLayers).forEach(l => map.removeLayer(l));
            map.removeLayer(hybridLabels);
            mapLayers[layer].addTo(map);
            if (layer === 'hybrid') hybridLabels.addTo(map);
        });
    });

    map.on('click', (e) => {
        if (document.getElementById('startLocation').value === '') {
            document.getElementById('startLocation').value = 
                `${e.latlng.lat.toFixed(5)}, ${e.latlng.lng.toFixed(5)}`;
            
            if (startMarker) map.removeLayer(startMarker);
            startMarker = L.marker([e.latlng.lat, e.latlng.lng], {
                icon: L.divIcon({
                    html: '<div style="background: #00ff00; width: 20px; height: 20px; border-radius: 50%; border: 3px solid white;"></div>',
                    className: '',
                    iconSize: [20, 20]
                })
            }).addTo(map).bindPopup('Start Location');
        } else if (document.getElementById('endLocation').value === '') {
            document.getElementById('endLocation').value = 
                `${e.latlng.lat.toFixed(5)}, ${e.latlng.lng.toFixed(5)}`;
            
            if (endMarker) map.removeLayer(endMarker);
            endMarker = L.marker([e.latlng.lat, e.latlng.lng], {
                icon: L.divIcon({
                    html: '<div style="background: #ff0000; width: 20px; height: 20px; border-radius: 50%; border: 3px solid white;"></div>',
                    className: '',
                    iconSize: [20, 20]
                })
            }).addTo(map).bindPopup('End Location');
        }
    });

    // Add Enter key support for location search
    document.getElementById('locationSearch').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            searchLocation();
        }
    });

    // Add welcome message to chat
    addChatMessage('ai', 'Hello! I\'m your AI traffic assistant. I can help you with:\n• Current traffic conditions\n• Route planning\n• Incident updates\n• Best travel times\n\nJust ask me anything!');
}

// Load cities
async function loadCities() {
    try {
        const response = await fetch('/api/traffic/cities');
        cities = await response.json();
        
        const select = document.getElementById('citySelect');
        cities.forEach(city => {
            const option = document.createElement('option');
            option.value = city.name;
            option.textContent = city.name;
            select.appendChild(option);
        });
    } catch (error) {
        console.error('Error loading cities:', error);
    }
}

// Change city
async function changeCity() {
    const cityName = document.getElementById('citySelect').value;
    if (!cityName) return;
    
    currentCity = cities.find(c => c.name === cityName);
    if (!currentCity) return;
    
    map.setView([currentCity.latitude, currentCity.longitude], 12);
    
    clearRoute();
    clearIncidents();
    
    await loadTrafficData();
    
    // Add system message to chat
    addChatMessage('ai', `Switched to ${cityName}. I'm now showing you real-time traffic data for this area. What would you like to know?`);
}

// Load traffic data
async function loadTrafficData() {
    if (!currentCity) return;
    
    try {
        const incidentsResponse = await fetch(`/api/traffic/incidents?bbox=${currentCity.bbox}`);
        currentIncidents = await incidentsResponse.json();
        
        displayIncidents(currentIncidents);
        updateStatistics(currentIncidents);
        displayIncidentsList(currentIncidents);
        
    } catch (error) {
        console.error('Error loading traffic data:', error);
    }
}

// Display incidents with timestamps
function displayIncidents(incidents) {
    clearIncidents();
    
    incidents.forEach(incident => {
        const severity = incident.severity || 1;
        const color = severity >= 3 ? '#dc3545' : severity >= 2 ? '#ffc107' : '#28a745';
        
        const marker = L.circleMarker([incident.latitude, incident.longitude], {
            radius: 8 + (severity * 2),
            fillColor: color,
            color: 'white',
            weight: 2,
            opacity: 1,
            fillOpacity: 0.7
        }).addTo(map);
        
        marker.bindPopup(`
            <strong>${incident.description}</strong><br>
            <strong>Location:</strong> ${incident.roadName || 'Unknown Road'}<br>
            <strong>Started:</strong> ${incident.startTime || 'Unknown'}<br>
            ${incident.endTime ? `<strong>Expected End:</strong> ${incident.endTime}<br>` : ''}
            <strong>Delay:</strong> ${incident.delay} minutes<br>
            <strong>Severity:</strong> ${severity}/4<br>
            <strong>Length:</strong> ${incident.length}m
        `);
        
        incidentMarkers.push(marker);
    });
}

function clearIncidents() {
    incidentMarkers.forEach(marker => map.removeLayer(marker));
    incidentMarkers = [];
}

// Search for specific location
async function searchLocation() {
    const query = document.getElementById('locationSearch').value.trim();
    
    if (!query) {
        alert('Please enter a location to search');
        return;
    }
    
    try {
        const cityContext = currentCity ? currentCity.name : '';
        const response = await fetch(
            `/api/traffic/geocode?query=${encodeURIComponent(query)}&cityContext=${encodeURIComponent(cityContext)}`
        );
        const result = await response.json();
        
        if (result.found) {
            // Zoom to location
            map.setView([result.latitude, result.longitude], 20);
            
            // Add marker
            if (searchMarker) map.removeLayer(searchMarker);
            
            searchMarker = L.marker([result.latitude, result.longitude], {
                icon: L.divIcon({
                    html: '<div class="search-marker">📍</div>',
                    className: '',
                    iconSize: [40, 40],
                    iconAnchor: [20, 40]
                })
            }).addTo(map);
            
            searchMarker.bindPopup(`
                <strong>📍 ${result.displayName}</strong><br>
                <button onclick="setAsStart(${result.latitude}, ${result.longitude})" 
                    style="margin-top:8px;padding:6px 12px;background:#667eea;color:white;border:none;border-radius:4px;cursor:pointer;">
                    Set as Start
                </button>
                <button onclick="setAsEnd(${result.latitude}, ${result.longitude})" 
                    style="margin-top:4px;padding:6px 12px;background:#f5576c;color:white;border:none;border-radius:4px;cursor:pointer;">
                    Set as End
                </button>
            `).openPopup();
            
            addChatMessage('ai', `Found "${result.displayName}". I've zoomed to this location on the map.`);
            
        } else {
            alert('Location not found. Try a different search term.');
        }
    } catch (error) {
        console.error('Error searching location:', error);
        alert('Error searching location. Please try again.');
    }
}

// Set search result as route start/end
function setAsStart(lat, lon) {
    document.getElementById('startLocation').value = `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
    if (startMarker) map.removeLayer(startMarker);
    startMarker = L.marker([lat, lon], {
        icon: L.divIcon({
            html: '<div style="background: #00ff00; width: 20px; height: 20px; border-radius: 50%; border: 3px solid white;"></div>',
            className: '',
            iconSize: [20, 20]
        })
    }).addTo(map).bindPopup('Start Location');
}

function setAsEnd(lat, lon) {
    document.getElementById('endLocation').value = `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
    if (endMarker) map.removeLayer(endMarker);
    endMarker = L.marker([lat, lon], {
        icon: L.divIcon({
            html: '<div style="background: #ff0000; width: 20px; height: 20px; border-radius: 50%; border: 3px solid white;"></div>',
            className: '',
            iconSize: [20, 20]
        })
    }).addTo(map).bindPopup('End Location');
}

function displayIncidentsList(incidents) {
    const list = document.getElementById('incidentsList');
    
    if (incidents.length === 0) {
        list.innerHTML = '<div style="opacity: 0.7;">No active incidents</div>';
        return;
    }
    
    list.innerHTML = incidents.map(incident => `
        <div class="incident-item">
            <div class="incident-title">${incident.description}</div>
            <div class="incident-detail">
                📍 ${incident.roadName || 'Unknown Road'}
            </div>
            <div class="incident-detail">
                ⏱️ Delay: ${incident.delay} min | Severity: ${incident.severity}/4
            </div>
            <div class="incident-time">
                Started: ${incident.startTime || 'Unknown'}
                ${incident.endTime ? `<br>Expected end: ${incident.endTime}` : ''}
            </div>
        </div>
    `).join('');
}

function updateStatistics(incidents) {
    document.getElementById('incidentCount').textContent = incidents.length;
    
    if (incidents.length > 0) {
        const avgDelay = incidents.reduce((sum, i) => sum + i.delay, 0) / incidents.length;
        const avgSeverity = incidents.reduce((sum, i) => sum + i.severity, 0) / incidents.length;
        
        document.getElementById('avgSpeed').textContent = Math.round(60 - avgDelay) + ' km/h';
        
        const congestion = avgSeverity >= 3 ? 'Heavy' : avgSeverity >= 2 ? 'Moderate' : 'Light';
        document.getElementById('congestionLevel').textContent = congestion;
    } else {
        document.getElementById('avgSpeed').textContent = '60 km/h';
        document.getElementById('congestionLevel').textContent = 'Free Flow';
    }
}

// Calculate route with traffic awareness
async function calculateRoute() {
    const startInput = document.getElementById('startLocation').value.trim();
    const endInput = document.getElementById('endLocation').value.trim();
    
    if (!startInput || !endInput) {
        alert('Please enter both start and end locations');
        return;
    }
    
    const start = startInput.split(',').map(s => parseFloat(s.trim()));
    const end = endInput.split(',').map(s => parseFloat(s.trim()));
    
    if (start.length !== 2 || end.length !== 2 || isNaN(start[0]) || isNaN(end[0])) {
        alert('Invalid coordinates format. Use: latitude, longitude');
        return;
    }
    
    const avoidTraffic = document.getElementById('avoidTraffic').checked;
    
    try {
        const response = await fetch(
            `/api/traffic/route?startLat=${start[0]}&startLon=${start[1]}&endLat=${end[0]}&endLon=${end[1]}&avoidTraffic=${avoidTraffic}`
        );
        const route = await response.json();
        
        displayRoute(route);
        
    } catch (error) {
        console.error('Error calculating route:', error);
        alert('Error calculating route. Please try again.');
    }
}

function displayRoute(route) {
    if (routeLayer) map.removeLayer(routeLayer);
    clearCongestionMarkers();
    
    if (!route.coordinates || route.coordinates.length === 0) {
        alert('No route found');
        return;
    }
    
    const color = route.recommended ? '#4caf50' : '#667eea';
    
    routeLayer = L.polyline(route.coordinates, {
        color: color,
        weight: 6,
        opacity: 0.8,
        lineJoin: 'round'
    }).addTo(map);
    
    // Display congestion points on route
    if (route.routeTrafficPoints && route.routeTrafficPoints.length > 0) {
        route.routeTrafficPoints.forEach(point => {
            if (point.congestion > 20) { // Only show significant congestion
                const marker = L.circleMarker([point.latitude, point.longitude], {
                    radius: 6,
                    fillColor: getCongestionColor(point.congestion),
                    color: 'white',
                    weight: 2,
                    opacity: 1,
                    fillOpacity: 0.8
                }).addTo(map);
                
                marker.bindPopup(`
                    <strong>Traffic Point</strong><br>
                    Congestion: ${point.congestion}%<br>
                    Speed: ${point.currentSpeed} km/h<br>
                    ${point.roadName || ''}
                `);
                
                congestionMarkers.push(marker);
            }
        });
    }
    
    map.fitBounds(routeLayer.getBounds(), { padding: [50, 50] });
    
    const distanceKm = (route.distance / 1000).toFixed(2);
    const durationMin = Math.round(route.duration / 60);
    
    let routeInfoHtml = `
        <div class="route-info">
            <strong>Route Information</strong><br>
            Distance: ${distanceKm} km<br>
            Duration: ${durationMin} minutes
    `;
    
    if (route.trafficLevel) {
        routeInfoHtml += `<br>Traffic: ${route.trafficLevel} (${route.averageCongestion}% congestion)`;
    }
    
    routeInfoHtml += '</div>';
    
    if (route.recommended) {
        routeInfoHtml += `
            <div class="route-success">
                ✅ ${route.recommendationReason}
            </div>
        `;
        addChatMessage('ai', `I found a better route! ${route.recommendationReason}. Check the green route on the map.`);
    } else if (route.averageCongestion && route.averageCongestion > 60) {
        routeInfoHtml += `
            <div class="route-warning">
                ⚠️ Heavy traffic detected on this route. Consider alternative times or routes.
            </div>
        `;
        addChatMessage('ai', `Warning: Heavy traffic detected on this route (${route.averageCongestion}% congestion). You might want to try a different time or ask me for alternatives.`);
    }
    
    document.getElementById('routeInfo').innerHTML = routeInfoHtml;
    
    routeLayer.bindPopup(`
        <strong>Route Information</strong><br>
        Distance: ${distanceKm} km<br>
        Duration: ${durationMin} minutes<br>
        ${route.trafficLevel ? `Traffic: ${route.trafficLevel}` : ''}
    `).openPopup();
}

function getCongestionColor(congestion) {
    if (congestion >= 70) return '#dc3545'; // Red
    if (congestion >= 50) return '#ffc107'; // Yellow
    if (congestion >= 30) return '#ff9800'; // Orange
    return '#4caf50'; // Green
}

function clearCongestionMarkers() {
    congestionMarkers.forEach(marker => map.removeLayer(marker));
    congestionMarkers = [];
}

function clearRoute() {
    if (routeLayer) {
        map.removeLayer(routeLayer);
        routeLayer = null;
    }
    if (startMarker) {
        map.removeLayer(startMarker);
        startMarker = null;
    }
    if (endMarker) {
        map.removeLayer(endMarker);
        endMarker = null;
    }
    clearCongestionMarkers();
    document.getElementById('startLocation').value = '';
    document.getElementById('endLocation').value = '';
    document.getElementById('routeInfo').innerHTML = '';
}

// Chat functionality
function addChatMessage(role, message) {
    const messagesDiv = document.getElementById('chatMessages');
    const messageDiv = document.createElement('div');
    messageDiv.className = `chat-message ${role}`;
    
    const bubbleDiv = document.createElement('div');
    bubbleDiv.className = 'chat-bubble';
    bubbleDiv.textContent = message;
    
    messageDiv.appendChild(bubbleDiv);
    messagesDiv.appendChild(messageDiv);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

async function sendChatMessage() {
    const input = document.getElementById('chatInput');
    const message = input.value.trim();
    
    if (!message) return;
    
    if (!currentCity) {
        addChatMessage('ai', 'Please select a city first so I can provide you with accurate traffic information.');
        input.value = '';
        return;
    }
    
    // Add user message
    addChatMessage('user', message);
    input.value = '';
    
    // Show typing indicator
    const typingDiv = document.createElement('div');
    typingDiv.className = 'chat-message ai';
    typingDiv.innerHTML = '<div class="chat-bubble pulse">Typing...</div>';
    typingDiv.id = 'typingIndicator';
    document.getElementById('chatMessages').appendChild(typingDiv);
    
    try {
        const response = await fetch('/api/traffic/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                sessionId: sessionId,
                message: message,
                city: currentCity.name,
                incidents: currentIncidents,
                flows: currentFlows
            })
        });
        
        const result = await response.json();
        
        // Remove typing indicator
        const typing = document.getElementById('typingIndicator');
        if (typing) typing.remove();
        
        // Add AI response
        addChatMessage('ai', result.message);
        
    } catch (error) {
        console.error('Error sending chat message:', error);
        const typing = document.getElementById('typingIndicator');
        if (typing) typing.remove();
        addChatMessage('ai', 'Sorry, I\'m having trouble connecting right now. Please try again.');
    }
}

// Auto-refresh traffic data
function startAutoRefresh() {
    setInterval(() => {
        if (currentCity) {
            loadTrafficData();
        }
    }, 120000); // 2 minutes
}

// Initialize application
window.onload = () => {
    initMap();
    loadCities();
    startAutoRefresh();
};