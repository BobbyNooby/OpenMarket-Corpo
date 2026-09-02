package stub

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func Test_not_deployed_is_501_with_service_named(t *testing.T) {
	rec := httptest.NewRecorder()
	NotDeployed("catalogue").ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/catalogue/items", nil))

	if rec.Code != http.StatusNotImplemented {
		t.Fatalf("stub must be 501, got %d", rec.Code)
	}
	var body map[string]string
	json.NewDecoder(rec.Body).Decode(&body)
	if body["code"] != "not_deployed" || body["message"] != "The catalogue service is not deployed yet" {
		t.Fatalf("unexpected envelope: %v", body)
	}
}
