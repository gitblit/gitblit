@ECHO OFF

SET CD=%~dp0
SET CD=%CD:~0,-1%
java -cp "%CD%\gitblit.jar";"%CD%\ext\*" com.gitblit.GitBlitServer --baseFolder data %*
