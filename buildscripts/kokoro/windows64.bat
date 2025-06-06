@rem ##########################################################################
@rem
@rem Builds artifacts for x86_64 into %WORKSPACE%\artifacts\
@rem
@rem ##########################################################################

type c:\VERSION

@rem Enter repo root
cd /d %~dp0\..\..

set WORKSPACE=T:\src\github\grpc-java
set ESCWORKSPACE=%WORKSPACE:\=\\%

@rem Clear JAVA_HOME to prevent a different Java version from being used
set JAVA_HOME=

mkdir grpc-java-helper64
cd grpc-java-helper64
call "%VS170COMNTOOLS%\..\..\VC\Auxiliary\Build\vcvars64.bat" || exit /b 1
call "%WORKSPACE%\buildscripts\make_dependencies.bat" || exit /b 1

cd "%WORKSPACE%"

SET TARGET_ARCH=x86_64
SET FAIL_ON_WARNINGS=true
SET PROTOBUF_VER=22.5
SET PKG_CONFIG_PATH=%ESCWORKSPACE%\\grpc-java-helper64\\protobuf-%PROTOBUF_VER%\\build\\protobuf-%PROTOBUF_VER%\\lib\\pkgconfig
SET VC_PROTOBUF_LIBS=/LIBPATH:%ESCWORKSPACE%\\grpc-java-helper64\\protobuf-%PROTOBUF_VER%\\build\\protobuf-%PROTOBUF_VER%\\lib
SET VC_PROTOBUF_INCLUDE=%ESCWORKSPACE%\\grpc-java-helper64\\protobuf-%PROTOBUF_VER%\\build\\protobuf-%PROTOBUF_VER%\\include
call :Get_Libs
SET GRADLE_FLAGS=-PtargetArch=%TARGET_ARCH% -PfailOnWarnings=%FAIL_ON_WARNINGS% -PvcProtobufLibs=%VC_PROTOBUF_LIBS% -PvcProtobufInclude=%VC_PROTOBUF_INCLUDE% -PskipAndroid=true
SET GRADLE_OPTS="-Dorg.gradle.jvmargs='-Xmx1g'"

@rem make sure no daemons have any files open
cmd.exe /C "%WORKSPACE%\gradlew.bat --stop"

cmd.exe /C "%WORKSPACE%\gradlew.bat  %GRADLE_FLAGS% -Dorg.gradle.parallel=false -PrepositoryDir=%WORKSPACE%\artifacts grpc-compiler:clean grpc-compiler:build grpc-compiler:publish" || exit /b 1

goto :eof
:Get_Libs
SetLocal EnableDelayedExpansion
set "libs_list="
for /f "tokens=*" %%a in ('pkg-config --libs protobuf') do (
  for %%b in (%%a) do (
    set lib=%%b
    set libfirst2char=!lib:~0,2!
    if !libfirst2char!==-l (
      @rem remove the leading -l
      set lib=!lib:~2!
      @rem remove spaces
      set lib=!lib: =!
      @rem Because protobuf is specified as libprotobuf and elsewhere
      if !lib! NEQ protobuf (
        set lib=!lib!.lib
        if "!libs_list!"=="" (
          set libs_list=!lib!
        ) else (
          set libs_list=!libs_list!,!lib!
        )
      )
    )
  )
)
EndLocal & set "VC_PROTOBUF_LIBS=%VC_PROTOBUF_LIBS%,%libs_list%" 
exit /b 0

