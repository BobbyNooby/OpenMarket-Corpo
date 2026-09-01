"""Smoke tests: the service imports and its health endpoints answer."""
from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def test_health_live():
    assert client.get("/health/live").status_code == 200


def test_health_ready():
    assert client.get("/health/ready").status_code == 200
