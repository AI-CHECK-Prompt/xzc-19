@echo off
REM 跨境冷链 GxP 合规系统 - Windows 一键启动脚本（飞检演示用）

echo ========================================
echo  跨境冷链 GxP 合规系统 - 一键启动
echo  药监局飞行检查专用
echo ========================================

cd /d "%~dp0"

REM 1) 检查 Docker
docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] Docker 未安装，请先安装 Docker Desktop
    pause
    exit /b 1
)

REM 2) 检查 docker compose
docker compose version >nul 2>&1
if %errorlevel% neq 0 (
    echo [警告] docker compose 不可用，尝试 docker-compose
    set DC=docker-compose
) else (
    set DC=docker compose
)

REM 3) 启动
echo [1/4] 启动 PostgreSQL + TimescaleDB ...
%DC% up -d postgres
echo [2/4] 等待数据库就绪 ...
timeout /t 15 /nobreak >nul

echo [3/4] 编译并启动后端 ...
cd backend
call mvn package -DskipTests -q
if %errorlevel% neq 0 (
    echo [错误] 后端编译失败
    pause
    exit /b 1
)
cd ..

echo [4/4] 启动完整服务 ...
%DC% up -d --build

echo ========================================
echo  启动完成！
echo  前端地址: http://localhost:8080/
echo  自检接口: http://localhost:8080/api/self-check
echo  健康检查: http://localhost:8080/api/self-check/ping
echo ========================================
echo.
echo  默认账号（密码统一为 password123）:
echo    admin / dispatcher / auditor / qa / customs
echo.
pause
