#define USE_VRMC_VRM_0_0
#define VRMC_VRM_0_0
#include <jni.h>
#include <string>
//#include <VRMC/VRM.h>
#include <filesystem>
//#include <fx/gltf.h>
#include <iostream>
#include <nlohmann/json.hpp>
#include <fstream>

using json = nlohmann::json;
using namespace std;

#pragma pack(push, 1)
struct GLBHeader {
    uint32_t magic;     // 0x46546C67 = "glTF"
    uint32_t version;   // 2
    uint32_t length;
};

struct ChunkHeader {
    uint32_t chunkLength;
    uint32_t chunkType; // 0x4E4F534A = "JSON", 0x004E4942 = "BIN"
};
#pragma pack(pop)


extern "C" JNIEXPORT jstring JNICALL
Java_com_example_useevtubingapp_MainActivity_convertVRMtoGLBcpp(
        JNIEnv* env,
        jobject /* this */,
        jstring inputPath,
        jstring outputPath) {

    // Конвертируем jstring в std::string
    const char* inputChars = env->GetStringUTFChars(inputPath, nullptr);
    string inputFile(inputChars);
    env->ReleaseStringUTFChars(inputPath, inputChars);

    const char* outputChars = env->GetStringUTFChars(outputPath, nullptr);
    string outputFile(outputChars);
    env->ReleaseStringUTFChars(outputPath, outputChars);

    // ЧИТАЕМ ВХОДНОЙ VRM ФАЙЛ
    ifstream file(inputFile, ios::binary);
    if (!file.is_open()) {
        // Возвращаем путь к исходному файлу при ошибке
        return inputPath;
    }

    vector<uint8_t> vrmData((istreambuf_iterator<char>(file)),
                                 istreambuf_iterator<char>());
    file.close();

    if (vrmData.size() < sizeof(GLBHeader)) {
        return inputPath;
    }

    // ПАРСИМ GLB СТРУКТУРУ
    const GLBHeader* header = reinterpret_cast<const GLBHeader*>(vrmData.data());
    if (header->magic != 0x46546C67) {
        return inputPath;
    }


    // Извлекаем JSON и бинарные данные
    string jsonStr;
    vector<uint8_t> binaryData;

    size_t offset = sizeof(GLBHeader);

    while (offset + sizeof(ChunkHeader) <= vrmData.size()) {
        const ChunkHeader* chunk = reinterpret_cast<const ChunkHeader*>(vrmData.data() + offset);
        size_t chunkDataStart = offset + sizeof(ChunkHeader);

        if (chunk->chunkType == 0x4E4F534A) { // JSON чанк
            if (chunkDataStart + chunk->chunkLength <= vrmData.size()) {
                jsonStr.assign(
                        reinterpret_cast<const char*>(vrmData.data() + chunkDataStart),
                        chunk->chunkLength
                );
            }
        }
        else if (chunk->chunkType == 0x004E4942) { // BIN чанк
            if (chunkDataStart + chunk->chunkLength <= vrmData.size()) {
                binaryData.assign(
                        vrmData.begin() + chunkDataStart,
                        vrmData.begin() + chunkDataStart + chunk->chunkLength
                );
            }
        }

        // Переходим к следующему чанку
        offset += sizeof(ChunkHeader) + chunk->chunkLength;
        while (offset % 4 != 0 && offset < vrmData.size()) offset++;
    }

    if (jsonStr.empty()) {
        return inputPath;
    }

    // ОЧИЩАЕМ JSON ОТ VRM РАСШИРЕНИЙ
    try {
        json gltfJson = json::parse(jsonStr);

        // Удаляем VRM-расширения
        if (gltfJson.contains("extensions")) {
            auto& extensions = gltfJson["extensions"];

            if (extensions.contains("VRM")) {
                extensions.erase("VRM");
            }

            if (extensions.contains("VRMC_vrm")) {
                extensions.erase("VRMC_vrm");
            }

            if (extensions.empty()) {
                gltfJson.erase("extensions");
            }
        }

        // Удаляем extras если есть
        if (gltfJson.contains("extras")) {
            gltfJson.erase("extras");
        }

        // Убеждаемся, что есть буфер
        if (!gltfJson.contains("buffers") || gltfJson["buffers"].empty()) {
            gltfJson["buffers"] = json::array();
            gltfJson["buffers"].push_back({
                {"uri", ""},
                {"byteLength", binaryData.size()}
            });
        } else {
            gltfJson["buffers"][0]["byteLength"] = binaryData.size();
            if (gltfJson["buffers"][0].contains("uri")) {
                gltfJson["buffers"][0]["uri"] = "";
            }
        }

        jsonStr = gltfJson.dump();

    } catch (const exception& e) {
        // Продолжаем с оригинальным JSON
    }

    // СОБИРАЕМ GLB ФАЙЛ
    vector<uint8_t> jsonBytes(jsonStr.begin(), jsonStr.end());
    while (jsonBytes.size() % 4 != 0) {
        jsonBytes.push_back(' ');
    }

    vector<uint8_t> binData = binaryData;
    while (binData.size() % 4 != 0) {
        binData.push_back(0);
    }

    // Вычисляем общий размер
    uint32_t totalSize = sizeof(GLBHeader) +
                         sizeof(ChunkHeader) + jsonBytes.size() +
                         sizeof(ChunkHeader) + binData.size();

    // Создаем выходной буфер
    vector<uint8_t> output(totalSize);
    uint8_t* ptr = output.data();

    // Пишем GLB Header
    GLBHeader* outHeader = reinterpret_cast<GLBHeader*>(ptr);
    outHeader->magic = 0x46546C67;
    outHeader->version = 2;
    outHeader->length = totalSize;
    ptr += sizeof(GLBHeader);

    // Пишем JSON Chunk
    ChunkHeader* jsonChunk = reinterpret_cast<ChunkHeader*>(ptr);
    jsonChunk->chunkLength = jsonBytes.size();
    jsonChunk->chunkType = 0x4E4F534A;
    ptr += sizeof(ChunkHeader);
    memcpy(ptr, jsonBytes.data(), jsonBytes.size());
    ptr += jsonBytes.size();

    // Пишем BIN Chunk
    ChunkHeader* binChunk = reinterpret_cast<ChunkHeader*>(ptr);
    binChunk->chunkLength = binData.size();
    binChunk->chunkType = 0x004E4942;
    ptr += sizeof(ChunkHeader);
    memcpy(ptr, binData.data(), binData.size());


    // ЗАПИСЫВАЕМ ВЫХОДНОЙ ФАЙЛ
    ofstream outputFileStream(outputFile, std::ios::binary);
    if (!outputFileStream.is_open()) {
        return inputPath;
    }

    outputFileStream.write(reinterpret_cast<const char*>(output.data()), output.size());
    outputFileStream.close();

    // Проверяем, что файл записан успешно
    if (outputFileStream.fail()) {
        return inputPath;
    }


    // ВОЗВРАЩАЕМ ПУТЬ К ВЫХОДНОМУ ФАЙЛУ ПРИ УСПЕХЕ
    return outputPath;
}