# The example provider's worker. Build from sdk/python:
#   docker build -f examples/dns_records/Dockerfile -t dns-records-provider .
FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN useradd --system --uid 10001 --no-create-home provider
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install .
COPY examples/dns_records/zones.py examples/dns_records/main.py ./
USER 10001
ENTRYPOINT ["python", "/app/main.py"]
CMD ["run"]
