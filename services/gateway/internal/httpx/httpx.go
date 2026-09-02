// Package httpx holds the gateway's JSON error envelope, mirroring the
// auth service's {code, message} shape so frontend error handling stays
// uniform no matter which hop produced the failure.
package httpx

import (
	"encoding/json"
	"net/http"
)

// Error writes the standard envelope. Never include internal details in
// the message — this body crosses the public boundary.
func Error(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{
		"code":    code,
		"message": message,
	})
}
