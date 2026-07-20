#!/bin/bash
# 跨境冷链 GxP 合规系统 - Linux/Mac 一键启动脚本

set -e
cd "$(dirname "$0")/.."

echo "========================================"
echo " 跨境冷链 GxP 合规系统 - 一键启动"
echo " 药监局飞行检查专用"
echo "========================================"

if ! command -v docker &> /dev/null; then
    echo "[错误] Docker 未安装"
    exit 1
fi

DC="docker compose"
if ! docker compose version &> /dev/null; then
    DC="docker-compose"
fi

echo "[1/4] 启动 PostgreSQL + TimescaleDB ..."
$DC up -d postgres
echo "[2/4] 等待数据库就绪 ..."
sleep 15

echo "[3/4] 编译后端 ..."
cd backend
mvn package -DskipTests -q
cd ..

echo "[4/4] 启动完整服务 ..."
$DC up -d --build

echo "========================================"
echo " 启动完成！"
echo " 前端地址: http://localhost:8080/"
echo " 自检接口: http://localhost:8080/api/self-check"
echo "========================================"
