import logging

LOG_FORMAT = "%(asctime)s %(levelname)s %(name)s - %(message)s"


def configure_logging() -> None:
    app_logger = logging.getLogger("app")
    app_logger.setLevel(logging.INFO)

    if app_logger.handlers:
        return

    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter(LOG_FORMAT))
    app_logger.addHandler(handler)
    app_logger.propagate = False
