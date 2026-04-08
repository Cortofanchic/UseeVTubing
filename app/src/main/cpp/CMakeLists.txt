# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("useevtubingapp")

# создаем библиотеку
add_library(native-lib SHARED
        native-lib.cpp
)

set(VRM_H_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/libs/VRM.h/include)
set(FX_GLTF_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/libs/fx-gltf/include)
set(NLOHMANN_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/libs/json/include)

# добавляем пути к заголовочным файлам
target_include_directories(native-lib PRIVATE
        ${FX_GLTF_INCLUDE_DIR}
        ${VRM_H_INCLUDE_DIR}
        ${NLOHMANN_INCLUDE_DIR}
)

# Подключаем системные библиотеки
find_library(log-lib log)
target_link_libraries(native-lib
        ${log-lib}
        #stdc++fs  # Для std::filesystem
)


