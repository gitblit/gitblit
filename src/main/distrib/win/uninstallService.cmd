@ECHO OFF

@REM arch = x86, amd64, or ia32
SET ARCH=amd64
SET CD=%~dp0
SET CD=%CD:~0,-1%

@REM Delete the gitblit service
"%CD%\%ARCH%\gitblit.exe" //DS//gitblit