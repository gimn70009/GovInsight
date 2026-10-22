import logging

import pytest


@pytest.fixture(autouse=True)
def capture_application_logs(request, monkeypatch):
    # App startup configures its own handler; caplog tests must not depend on test order.
    if "caplog" in request.fixturenames:
        monkeypatch.setattr(logging.getLogger("app"), "propagate", True)
