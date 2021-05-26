@ECHO OFF

@REM --------------------------------------------------------------------------
@REM This is for reindexing Tickets with Lucene.
@REM
@REM Since the Tickets feature is undergoing massive churn it may be necessary 
@REM to reindex tickets due to model or index changes.
@REM
@REM usage:
@REM     reindex-tickets <baseFolder>
@REM
@REM --------------------------------------------------------------------------

SET CD=%~dp0
SET CD=%CD:~0,-1%

if [%1]==[] goto nobasefolder

java -cp gitblit.jar;"%CD%\ext\*" com.gitblit.ReindexTickets --baseFolder %1
goto end

:nobasefolder
echo "Please specify your baseFolder!"
echo
echo "    reindex-tickets c:/gitblit-data"
echo

:end