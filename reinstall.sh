#!/bin/bash

echo "🔨 开始编译..."
./gradlew assembleDebug -x test

if [ $? -eq 0 ]; then
    echo "✅ 编译成功！"
    
    echo ""
    echo "🗑️  卸载旧版本..."
    adb uninstall com.narc.arclient
    
    echo ""
    echo "📦 安装新版本..."
    adb install app/build/outputs/apk/debug/app-debug.apk
    
    if [ $? -eq 0 ]; then
        echo ""
        echo "✅ 安装成功！"
        echo "🚀 可以启动应用测试了"
    else
        echo ""
        echo "❌ 安装失败，请检查设备连接"
    fi
else
    echo "❌ 编译失败，请检查代码错误"
fi
