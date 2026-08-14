import logging
import sys

LOG_FORMAT = "%(asctime)s %(levelname)s %(name)s - %(message)s"


def configure_logging() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    app_logger = logging.getLogger("app")
    app_logger.setLevel(logging.INFO)

    if app_logger.handlers:
        return

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(LOG_FORMAT))
    app_logger.addHandler(handler)
    app_logger.propagate = False
