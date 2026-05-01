#define USE_VRMC_VRM_0_0
#define VRMC_VRM_0_0

#include <jni.h>
#include <string>
#include <nlohmann/json.hpp>
#include <fstream>
#include <vector>
#include <algorithm>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "VRM2GLB"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;
using namespace std;

#pragma pack(push, 1)
struct GLBHeader {
    uint32_t magic;     // "glTF" = 0x46546C67
    uint32_t version;   // 2
    uint32_t length;
};

struct ChunkHeader {
    uint32_t chunkLength;
    uint32_t chunkType; // "JSON" = 0x4E4F534A, "BIN" = 0x004E4942
};
#pragma pack(pop)

// Расширения VRM, которые ломают большинство glTF-загрузчиков
const vector<string> UNSUPPORTED_EXTENSIONS = {
        "VRMC_vrm",
        "VRMC_springBone",
        "VRMC_node_constraint",
        "VRMC_look_at",
        "VRMC_vrm_animation",
        "VRMC_vrm_animation_controller",
        "VRM"
};

// ====================== 1. РАСПАРСИТЬ GLB ======================
string extractJsonChunk(const vector<uint8_t>& data) {
    if (data.size() < sizeof(GLBHeader)) return "";
    size_t offset = sizeof(GLBHeader);

    while (offset + sizeof(ChunkHeader) <= data.size()) {
        const ChunkHeader* chunk = reinterpret_cast<const ChunkHeader*>(data.data() + offset);
        size_t dataStart = offset + sizeof(ChunkHeader);

        if (chunk->chunkType == 0x4E4F534A) { // JSON
            size_t size = std::min(chunk->chunkLength, static_cast<uint32_t>(data.size() - dataStart));
            return string(reinterpret_cast<const char*>(data.data() + dataStart), size);
        }

        offset += sizeof(ChunkHeader) + chunk->chunkLength;
        while (offset % 4 != 0 && offset < data.size()) offset++;
    }
    return "";
}

vector<uint8_t> extractBinChunk(const vector<uint8_t>& data) {
    if (data.size() < sizeof(GLBHeader)) return {};
    size_t offset = sizeof(GLBHeader);

    while (offset + sizeof(ChunkHeader) <= data.size()) {
        const ChunkHeader* chunk = reinterpret_cast<const ChunkHeader*>(data.data() + offset);
        size_t dataStart = offset + sizeof(ChunkHeader);

        if (chunk->chunkType == 0x004E4942) { // BIN
            size_t size = std::min(chunk->chunkLength, static_cast<uint32_t>(data.size() - dataStart));
            return vector<uint8_t>(data.begin() + dataStart, data.begin() + dataStart + size);
        }

        offset += sizeof(ChunkHeader) + chunk->chunkLength;
        while (offset % 4 != 0 && offset < data.size()) offset++;
    }
    return {};
}

// ====================== 2. ОБРАБОТКА JSON ======================
string processVRMJson(const string& originalJson) {
    try {
        json gltf = json::parse(originalJson);

        LOGD("Starting VRM to GLB processing. Nodes: %zu, Meshes: %zu",
             gltf.value("nodes", json::array()).size(),
             gltf.value("meshes", json::array()).size());

        // === 1. Удаляем все опасные VRM-расширения ===
        for (const string& field : {"extensionsUsed", "extensionsRequired"}) {
            if (gltf.contains(field) && gltf[field].is_array()) {
                vector<string> filtered;
                for (const auto& ext : gltf[field]) {
                    if (!ext.is_string()) continue;
                    string name = ext.get<string>();
                    if (find(UNSUPPORTED_EXTENSIONS.begin(), UNSUPPORTED_EXTENSIONS.end(), name) == UNSUPPORTED_EXTENSIONS.end()) {
                        filtered.push_back(name);
                    } else {
                        LOGD("Removed extension from %s: %s", field.c_str(), name.c_str());
                    }
                }
                if (!filtered.empty()) {
                    gltf[field] = filtered;
                } else {
                    gltf.erase(field);
                }
            }
        }

        // Удаляем топ-левел extensions
        if (gltf.contains("extensions") && gltf["extensions"].is_object()) {
            json& exts = gltf["extensions"];
            vector<string> toErase;
            for (auto& [key, _] : exts.items()) {
                if (find(UNSUPPORTED_EXTENSIONS.begin(), UNSUPPORTED_EXTENSIONS.end(), key) != UNSUPPORTED_EXTENSIONS.end()) {
                    LOGD("Removing top-level VRM extension: %s", key.c_str());
                    toErase.push_back(key);
                }
            }
            for (const auto& k : toErase) exts.erase(k);
            if (exts.empty()) gltf.erase("extensions");
        }

        // Удаляем расширения внутри nodes
        if (gltf.contains("nodes") && gltf["nodes"].is_array()) {
            for (auto& node : gltf["nodes"]) {
                if (node.contains("extensions") && node["extensions"].is_object()) {
                    json& nodeExts = node["extensions"];
                    vector<string> toErase;
                    for (auto& [key, _] : nodeExts.items()) {
                        if (find(UNSUPPORTED_EXTENSIONS.begin(), UNSUPPORTED_EXTENSIONS.end(), key) != UNSUPPORTED_EXTENSIONS.end()) {
                            toErase.push_back(key);
                        }
                    }
                    for (const auto& k : toErase) nodeExts.erase(k);
                    if (nodeExts.empty()) node.erase("extensions");
                }
            }
        }

        // === 2. Сохраняем все кости (nodes) без изменений ===
        // Ничего не удаляем из nodes — оставляем иерархию и имена как есть

        // === 3. Делаем отдельные skins для каждого mesh (решает проблему с несколькими мешами) ===
        if (gltf.contains("skins") && !gltf["skins"].empty() &&
            gltf.contains("meshes") && !gltf["meshes"].empty()) {

            LOGD("Creating per-mesh skins for compatibility...");

            json originalSkin = gltf["skins"][0];
            json newSkins = json::array();

            // Оригинальный skin
            newSkins.push_back(originalSkin);

            // Отдельный skin для каждого mesh
            for (size_t i = 0; i < gltf["meshes"].size(); ++i) {
                json meshSkin = originalSkin;
                meshSkin["name"] = "skin_mesh_" + std::to_string(i);
                newSkins.push_back(meshSkin);
            }

            gltf["skins"] = newSkins;

            // Назначаем каждому примитиву свой skin
            for (size_t meshIdx = 0; meshIdx < gltf["meshes"].size(); ++meshIdx) {
                auto& mesh = gltf["meshes"][meshIdx];
                if (mesh.contains("primitives") && mesh["primitives"].is_array()) {
                    for (auto& primitive : mesh["primitives"]) {
                        if (primitive.contains("attributes")) {
                            primitive["skin"] = meshIdx + 1;  // +1 потому что 0 — оригинальный
                        }
                    }
                }
            }

            LOGD("Created %zu skins (1 original + %zu per-mesh)", newSkins.size(), gltf["meshes"].size());
        }

        // === 4. Минимальные исправления glTF ===
        if (!gltf.contains("asset")) {
            gltf["asset"] = {{"version", "2.0"}, {"generator", "VRM2GLB Converter"}};
        } else {
            gltf["asset"]["version"] = "2.0";
            if (!gltf["asset"].contains("generator"))
                gltf["asset"]["generator"] = "VRM2GLB Converter";
        }

        if (!gltf.contains("scene") && gltf.contains("scenes") && !gltf["scenes"].empty()) {
            gltf["scene"] = 0;
        }

        LOGD("Processing completed successfully.");
        return gltf.dump(4);  // красивый вывод с отступами (для отладки)
    }
    catch (const exception& e) {
        LOGE("JSON processing error: %s", e.what());
        return "";
    }
}

// ====================== 3. СОБРАТЬ GLB ======================
vector<uint8_t> createGLBFile(const string& jsonStr, const vector<uint8_t>& binData) {
    vector<uint8_t> jsonBytes(jsonStr.begin(), jsonStr.end());
    while (jsonBytes.size() % 4 != 0) jsonBytes.push_back(' ');

    vector<uint8_t> finalBin = binData;
    while (finalBin.size() % 4 != 0) finalBin.push_back(0);

    uint32_t totalSize = sizeof(GLBHeader) +
                         sizeof(ChunkHeader) + jsonBytes.size() +
                         (finalBin.empty() ? 0 : sizeof(ChunkHeader) + finalBin.size());

    vector<uint8_t> output(totalSize, 0);
    uint8_t* ptr = output.data();

    // GLB Header
    GLBHeader* header = reinterpret_cast<GLBHeader*>(ptr);
    header->magic = 0x46546C67;
    header->version = 2;
    header->length = totalSize;
    ptr += sizeof(GLBHeader);

    // JSON Chunk
    ChunkHeader* jsonChunk = reinterpret_cast<ChunkHeader*>(ptr);
    jsonChunk->chunkLength = jsonBytes.size();
    jsonChunk->chunkType = 0x4E4F534A;
    ptr += sizeof(ChunkHeader);
    memcpy(ptr, jsonBytes.data(), jsonBytes.size());
    ptr += jsonBytes.size();

    // BIN Chunk
    if (!finalBin.empty()) {
        ChunkHeader* binChunk = reinterpret_cast<ChunkHeader*>(ptr);
        binChunk->chunkLength = finalBin.size();
        binChunk->chunkType = 0x004E4942;
        ptr += sizeof(ChunkHeader);
        memcpy(ptr, finalBin.data(), finalBin.size());
    }

    return output;
}

// ====================== JNI ======================
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_useevtubingapp_MainActivity_convertVRMtoGLBcpp(
        JNIEnv* env,
        jobject /* this */,
        jstring jInputPath,
        jstring jOutputPath) {

    const char* inChars = env->GetStringUTFChars(jInputPath, nullptr);
    const char* outChars = env->GetStringUTFChars(jOutputPath, nullptr);
    string inputPath = inChars;
    string outputPath = outChars;
    env->ReleaseStringUTFChars(jInputPath, inChars);
    env->ReleaseStringUTFChars(jOutputPath, outChars);

    LOGD("=== VRM to GLB conversion started ===\nInput: %s\nOutput: %s",
         inputPath.c_str(), outputPath.c_str());

    // Читаем VRM файл
    ifstream file(inputPath, ios::binary | ios::ate);
    if (!file.is_open()) {
        LOGE("Failed to open input file: %s", inputPath.c_str());
        return jInputPath;  // возвращаем оригинальный путь при ошибке
    }

    size_t fileSize = file.tellg();
    file.seekg(0, ios::beg);

    vector<uint8_t> vrmData(fileSize);
    file.read(reinterpret_cast<char*>(vrmData.data()), fileSize);
    file.close();

    if (vrmData.size() < sizeof(GLBHeader)) {
        LOGE("File too small to be GLB/VRM");
        return jInputPath;
    }

    // Проверка заголовка
    const GLBHeader* hdr = reinterpret_cast<const GLBHeader*>(vrmData.data());
    if (hdr->magic != 0x46546C67) {
        LOGE("Invalid GLB magic number");
        return jInputPath;
    }

    string jsonStr = extractJsonChunk(vrmData);
    vector<uint8_t> binData = extractBinChunk(vrmData);

    if (jsonStr.empty()) {
        LOGE("No JSON chunk found in VRM file");
        return jInputPath;
    }

    LOGD("Original: JSON=%zu bytes, BIN=%zu bytes", jsonStr.size(), binData.size());

    // Обрабатываем JSON
    string processedJson = processVRMJson(jsonStr);
    if (processedJson.empty()) {
        LOGE("Failed to process VRM JSON");
        return jInputPath;
    }

    // Создаём новый GLB
    vector<uint8_t> glbData = createGLBFile(processedJson, binData);

    // Записываем результат
    ofstream outFile(outputPath, ios::binary);
    if (!outFile.is_open()) {
        LOGE("Failed to create output file: %s", outputPath.c_str());
        return jInputPath;
    }

    outFile.write(reinterpret_cast<const char*>(glbData.data()), glbData.size());
    outFile.close();

    LOGD("=== Conversion SUCCESS! ===\nOutput GLB size: %zu bytes", glbData.size());
    return env->NewStringUTF(outputPath.c_str());  // возвращаем путь к GLB при успехе
}