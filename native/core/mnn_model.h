//
// Created by Zhenxiang Chen on 06/02/23.
//

#ifndef SUPERVIDEO_MNN_MODEL_H
#define SUPERVIDEO_MNN_MODEL_H

#include <cstdint>
#include <vector>

#include <jni.h>

/** Owned copy of a serialized MNN model. */
struct mnn_model {
    std::vector<int8_t> bytes;

    const void* data() const { return bytes.data(); }
    size_t size() const { return bytes.size(); }
};

mnn_model mnn_model_from_jbytes(JNIEnv* env, jbyteArray array);

#endif //SUPERVIDEO_MNN_MODEL_H
