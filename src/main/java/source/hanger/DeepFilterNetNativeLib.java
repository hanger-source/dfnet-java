package source.hanger;

import com.sun.jna.Native;
import com.sun.jna.Library;
import com.sun.jna.Pointer;

public interface DeepFilterNetNativeLib extends Library {

    DeepFilterNetNativeLib INSTANCE = Native.load("df", DeepFilterNetNativeLib.class);

    // C 接口映射
    Pointer df_create(String path, float attenLim, String logLevel);

    // 映射 df_get_frame_length
    // C: pub unsafe extern "C" fn df_get_frame_length(st: *mut DFState) -> usize
    // Java: int df_get_frame_length(Pointer st)
    int df_get_frame_length(Pointer st);

    // 映射 df_process_frame
    // C: pub unsafe extern "C" fn df_process_frame(st: *mut DFState, input: float[], output: float[]) -> c_float
    // Java: float df_process_frame(Pointer st, float[] input, float[] output)
    // JNA 会处理 float[] 到 float* 的映射
    float df_process_frame(Pointer st, float[] input, float[] output);

    // 映射 df_free
    // C: pub unsafe extern "C" fn df_free(model: *mut DFState)
    // Java: void df_free(Pointer model)
    void df_free(Pointer model);

    // 映射 df_set_atten_lim
    // C: pub unsafe extern "C" fn df_set_atten_lim(st: *mut DFState, lim_db: f32)
    void df_set_atten_lim(Pointer st, float lim_db);

    // 映射 df_set_post_filter_beta
    // C: pub unsafe extern "C" fn df_set_post_filter_beta(st: *mut DFState, beta: f32)
    void df_set_post_filter_beta(Pointer st, float beta);

    // 映射 df_next_log_msg
    // C: pub unsafe extern "C" fn df_next_log_msg(st: *mut DFState) -> *mut c_char
    // Java: Pointer df_next_log_msg(Pointer st)
    Pointer df_next_log_msg(Pointer st);

    // 映射 df_free_log_msg
    // C: pub unsafe extern "C" fn df_free_log_msg(ptr: *mut c_char)
    void df_free_log_msg(Pointer ptr);

    // 注意：DynArray 和 df_coef_size, df_gain_size 涉及到 C 结构体映射，这里为了简化暂不实现。
    // 如果需要，JNA 提供了 Structure 类来映射 C 结构体。
}
