@echo off
title Compiling...
cd src
javac -cp . -d ../bin/ ./*java
pause