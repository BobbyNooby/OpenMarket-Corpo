package main

import (
	"encoding/json"
	"net/http"
	"os"
	"time"
)

type ServiceHealth struct {
	Name   string `json:"name"`
	Status string `json:"status"`
	URL    string `json:"url"`
}

type HealthResponse struct {
	Status   string           `json:"status"`
	Services []ServiceHealth  `json:"services"`
}

func envOrDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

var services = []struct {
	Name string
	URL  string
}{
	{"auth", envOrDefault("AUTH_URL", "http://localhost:8080")},
	{"catalogue", envOrDefault("CATALOGUE_URL", "http://localhost:8081")},
	{"messaging", envOrDefault("MESSAGING_URL", "http://localhost:8082")},
	{"presence", envOrDefault("PRESENCE_URL", "http://localhost:8083")},
	{"assets", envOrDefault("ASSETS_URL", "http://localhost:8084")},
	{"admin", envOrDefault("ADMIN_URL", "http://localhost:8085")},
}

func healthLive(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func healthReady(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ready"})
}

func healthSystem(w http.ResponseWriter, r *http.Request) {
	client := &http.Client{Timeout: 2 * time.Second}
	results := make([]ServiceHealth, len(services))

	for i, svc := range services {
		results[i] = ServiceHealth{Name: svc.Name, URL: svc.URL}
		resp, err := client.Get(svc.URL + "/health/ready")
		if err != nil {
			results[i].Status = "unreachable"
			continue
		}
		resp.Body.Close()
		if resp.StatusCode == 200 {
			results[i].Status = "healthy"
		} else {
			results[i].Status = "degraded"
		}
	}

	allHealthy := true
	for _, s := range results {
		if s.Status != "healthy" {
			allHealthy = false
			break
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(func() int {
		if allHealthy {
			return http.StatusOK
		}
		return http.StatusServiceUnavailable
	}())
	json.NewEncoder(w).Encode(HealthResponse{
		Status:   func() string { if allHealthy { return "healthy" }; return "degraded" }(),
		Services: results,
	})
}

func main() {
	http.HandleFunc("/health/live", healthLive)
	http.HandleFunc("/health/ready", healthReady)
	http.HandleFunc("/health/system", healthSystem)
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{
			"service": "gateway",
			"status":  "ok",
			"version": "0.1.0",
		})
	})

	port := envOrDefault("PORT", "3000")
	http.ListenAndServe(":"+port, nil)
}
