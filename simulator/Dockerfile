FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
COPY simulator.py .
CMD ["python", "simulator.py", "--http-url", "http://backend:8080"]
