@ECHO OFF

SET CD=%~dp0
SET CD=%CD:~0,-1%
java -cp gitblit.jar;"%CD%\ext\*" com.gitblit.GitBlitServer --stop --baseFolder data %*
