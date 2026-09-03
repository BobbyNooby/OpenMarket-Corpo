FROM python:3.12-slim
WORKDIR /app
# deps first so source churn doesn't re-download; installs uvicorn's
# console script into /usr/local/bin (the old multi-stage only copied
# site-packages and lost the binary — container never started)
COPY pyproject.toml ./
RUN pip install --no-cache-dir .
COPY main.py ./
RUN useradd --system --uid 1500 --home /app appuser && chown -R appuser /app
USER appuser
EXPOSE 8083
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8083"]
