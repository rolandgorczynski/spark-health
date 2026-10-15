FROM python:3.12-slim
WORKDIR /app
COPY --chmod=0444 receiver.py /app/receiver.py
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
CMD ["python", "receiver.py"]
