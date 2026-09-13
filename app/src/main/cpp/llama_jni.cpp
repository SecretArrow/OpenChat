/*
 * OpenChat — on-device GGUF inference JNI bridge (llama.cpp, pinned b10941).
 *
 * One engine instance per model load. A completion runs synchronously on the
 * calling (background) thread and streams pieces through a Kotlin callback:
 * onToken(piece) returning false cleanly stops the loop (cancellation path).
 *
 * CPU-only by design: Android devices expose heterogeneous accelerators and
 * the honest lowest-common-denominator is CPU with mmap — no fake GPU claims.
 */
#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include "llama.h"

namespace {

std::atomic<bool> g_cancel{false};

struct Engine {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
};

jstring toJString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeIsAvailable(JNIEnv*, jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeLoad(
        JNIEnv* env, jobject, jstring jpath, jint nCtx, jint nThreads) {
    if (jpath == nullptr) return 0;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return 0;

    llama_backend_init();
    auto mparams = llama_model_default_params();
    mparams.use_mmap    = true;   // stream weights from flash; resident RSS stays near file size
    mparams.use_mlock   = false;
    mparams.n_gpu_layers = 0;     // CPU-only (honest baseline)
    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) return 0;

    auto cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(nCtx);
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }

    auto* e = new Engine();
    e->model = model;
    e->ctx   = ctx;
    e->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeRequestCancel(JNIEnv*, jobject) {
    g_cancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e == nullptr) return;
    if (e->ctx   != nullptr) llama_free(e->ctx);
    if (e->model != nullptr) llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_openchat_android_ai_local_LlamaBridge_nativeStartCompletion(
        JNIEnv* env, jobject, jlong handle,
        jobjectArray roles, jobjectArray contents,
        jint maxTokens, jfloat temp, jobject callback) {

    auto* e = reinterpret_cast<Engine*>(handle);
    if (e == nullptr || e->model == nullptr || e->ctx == nullptr || e->vocab == nullptr) {
        return toJString(env, "unloaded");
    }
    g_cancel = false;

    // ---- build chat messages -------------------------------------------------
    const jsize nMsg = env->GetArrayLength(roles);
    if (nMsg <= 0 || nMsg != env->GetArrayLength(contents)) return toJString(env, "no-messages");
    std::vector<std::string> roleS(static_cast<size_t>(nMsg));
    std::vector<std::string> contS(static_cast<size_t>(nMsg));
    for (jsize i = 0; i < nMsg; i++) {
        jstring jr = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        jstring jc = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        const char* r = env->GetStringUTFChars(jr, nullptr);
        const char* c = env->GetStringUTFChars(jc, nullptr);
        roleS[static_cast<size_t>(i)] = (r != nullptr) ? r : "";
        contS[static_cast<size_t>(i)] = (c != nullptr) ? c : "";
        if (r != nullptr) env->ReleaseStringUTFChars(jr, r);
        if (c != nullptr) env->ReleaseStringUTFChars(jc, c);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    std::vector<llama_chat_message> chat(static_cast<size_t>(nMsg));
    for (jsize i = 0; i < nMsg; i++) {
        chat[static_cast<size_t>(i)].role    = roleS[static_cast<size_t>(i)].c_str();
        chat[static_cast<size_t>(i)].content = contS[static_cast<size_t>(i)].c_str();
    }

    // ---- apply the model's own chat template --------------------------------
    const char* tmpl = llama_model_chat_template(e->model, nullptr);
    int32_t need = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, nullptr, 0);
    if (need <= 0) need = 16384;
    std::vector<char> pbuf(static_cast<size_t>(need) + 16);
    int32_t plen = llama_chat_apply_template(
            tmpl, chat.data(), chat.size(), true, pbuf.data(), static_cast<int32_t>(pbuf.size()));
    if (plen <= 0) return toJString(env, "template");
    std::string prompt(pbuf.data(), static_cast<size_t>(plen));

    // ---- tokenize ------------------------------------------------------------
    int32_t nPrompt = llama_tokenize(
            e->vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, false, true);
    if (nPrompt <= 0) return toJString(env, "tokenize");
    std::vector<llama_token> tokens(static_cast<size_t>(nPrompt));
    int32_t got = llama_tokenize(
            e->vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
            tokens.data(), nPrompt, false, true);
    if (got <= 0) return toJString(env, "tokenize");
    tokens.resize(static_cast<size_t>(got));

    const uint32_t nCtx = llama_n_ctx(e->ctx);
    if (tokens.size() + static_cast<size_t>(maxTokens) + 4 > nCtx) {
        return toJString(env, "overflow");
    }

    // ---- sampler chain -------------------------------------------------------
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    if (temp < 0.05f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jmethodID mid = env->GetMethodID(
            env->GetObjectClass(callback), "onToken", "(Ljava/lang/String;)Z");

    // ---- generation loop -----------------------------------------------------
    llama_memory_clear(llama_get_memory(e->ctx), true);

    std::string finish = "stop";
    int nCur = 0;
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(e->ctx, batch) != 0) {
        llama_sampler_free(smpl);
        return toJString(env, "decode");
    }
    nCur = static_cast<int>(tokens.size());

    for (int i = 0; i < maxTokens; i++) {
        if (g_cancel) { finish = "cancelled"; break; }
        llama_token id = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, id)) break;

        char piece[512];
        int32_t np = llama_token_to_piece(e->vocab, id, piece, sizeof(piece), 0, true);
        if (np > 0) {
            jstring jpiece = env->NewStringUTF(std::string(piece, static_cast<size_t>(np)).c_str());
            if (jpiece != nullptr) {
                jboolean cont = env->CallBooleanMethod(callback, mid, jpiece);
                env->DeleteLocalRef(jpiece);
                if (!cont) { finish = "cancelled"; break; }
            }
        }
        llama_sampler_accept(smpl, id);

        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(e->ctx, next) != 0) { finish = "decode"; break; }
        nCur++;
        if (nCur >= static_cast<int>(nCtx)) { finish = "length"; break; }
    }

    llama_sampler_free(smpl);
    return toJString(env, finish);
}
