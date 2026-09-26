//
// Created by Zhenxiang Chen on 06/02/23.
//

#include "mnn_model.h"

mnn_model mnn_model_from_jbytes(JNIEnv* env, jbyteArray array) {
    mnn_model model;
    const jsize length = env->GetArrayLength(array);
    model.bytes.resize(length);
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(model.bytes.data()));
    return model;
}
