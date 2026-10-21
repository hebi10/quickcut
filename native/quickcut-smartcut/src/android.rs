use crate::{cut_ranges, cut_ranges_copy_video, suggest_copy_ranges, Callbacks};
use jni::{JNIEnv, JavaVM, objects::{GlobalRef, JClass, JLongArray, JObject, JString, JThrowable, JValue}, sys::{jboolean, jlong, jlongArray}};
use std::{panic::{catch_unwind, AssertUnwindSafe}, ptr, sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}}};

#[cfg(target_os = "android")]
fn warn(message: &str) {
    #[link(name = "log")]
    extern "C" { fn __android_log_write(priority: i32, tag: *const std::ffi::c_char, text: *const std::ffi::c_char) -> i32; }
    let message = std::ffi::CString::new(message.replace('\0', "?")).unwrap();
    unsafe { __android_log_write(5, c"QuickCutExport".as_ptr(), message.as_ptr()); }
}
#[cfg(not(target_os = "android"))]
fn warn(message: &str) {
    use std::io::Write;
    let _ = writeln!(std::io::stderr().lock(), "{message}");
}

enum CallbackFailure { Java(GlobalRef), Native(String) }

struct JavaCallbacks {
    vm: JavaVM,
    object: GlobalRef,
    failure: Mutex<Option<CallbackFailure>>,
    cancelled: AtomicBool,
}

impl JavaCallbacks {
    fn invoke(&self, name: &str, signature: &str, args: &[JValue]) -> Option<bool> {
        if self.failure.lock().unwrap_or_else(|p| p.into_inner()).is_some() { return None; }
        let mut env = match self.vm.attach_current_thread() {
            Ok(env) => env,
            Err(error) => {
                *self.failure.lock().unwrap_or_else(|p| p.into_inner()) = Some(CallbackFailure::Native(error.to_string()));
                return None;
            }
        };
        match env.call_method(self.object.as_obj(), name, signature, args) {
            Ok(value) => Some(if signature == "()Z" { value.z().unwrap_or(true) } else { false }),
            Err(error) => {
                // Store and rethrow on the original JNI thread. Clearing here
                // only makes it legal to create the throwable's global ref;
                // the exception is never converted into a successful cut.
                let failure = if env.exception_check().unwrap_or(false) {
                    match env.exception_occurred() {
                        Ok(throwable) => {
                            let _ = env.exception_clear();
                            match env.new_global_ref(throwable) {
                                Ok(reference) => CallbackFailure::Java(reference),
                                Err(other) => CallbackFailure::Native(other.to_string()),
                            }
                        },
                        Err(other) => CallbackFailure::Native(other.to_string()),
                    }
                } else { CallbackFailure::Native(error.to_string()) };
                *self.failure.lock().unwrap_or_else(|p| p.into_inner()) = Some(failure);
                None
            }
        }
    }
}

impl Callbacks for JavaCallbacks {
    fn is_cancelled(&self) -> bool {
        if self.cancelled.load(Ordering::Relaxed) { return true; }
        let stopped = self.invoke("isCancelled", "()Z", &[]).unwrap_or(true);
        if stopped { self.cancelled.store(true, Ordering::Relaxed); }
        stopped
    }
    fn progress(&self, percent: i32) {
        if !self.cancelled.load(Ordering::Relaxed) {
            let _ = self.invoke("onProgress", "(I)V", &[JValue::Int(percent)]);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hebi_quickcut_media_NativeSmartCutBridge_cut(
    env: JNIEnv, _class: JClass, input: JString, output: JString,
    start_us: jlong, end_us: jlong, muted: jboolean, callbacks: JObject,
) -> jlongArray {
    run_operation(env, input, Ranges::Single(start_us, end_us), muted, callbacks, Operation::Cut(output, false))
}

#[no_mangle]
pub extern "system" fn Java_com_hebi_quickcut_media_NativeSmartCutBridge_cutRanges(
    env: JNIEnv, _class: JClass, input: JString, output: JString,
    ranges: JLongArray, muted: jboolean, callbacks: JObject,
) -> jlongArray {
    run_operation(env, input, Ranges::Multiple(ranges), muted, callbacks, Operation::Cut(output, false))
}

#[no_mangle]
pub extern "system" fn Java_com_hebi_quickcut_media_NativeSmartCutBridge_cutRangesCopyVideo(
    env: JNIEnv, _class: JClass, input: JString, output: JString,
    ranges: JLongArray, muted: jboolean, callbacks: JObject,
) -> jlongArray {
    run_operation(env, input, Ranges::Multiple(ranges), muted, callbacks, Operation::Cut(output, true))
}

#[no_mangle]
pub extern "system" fn Java_com_hebi_quickcut_media_NativeSmartCutBridge_suggestCopyRanges(
    env: JNIEnv, _class: JClass, input: JString, ranges: JLongArray,
    source_end_us: jlong, muted: jboolean, callbacks: JObject,
) -> jlongArray {
    run_operation(env, input, Ranges::Multiple(ranges), muted, callbacks, Operation::Suggest(source_end_us))
}

enum Ranges<'a> { Single(i64, i64), Multiple(JLongArray<'a>) }
enum Operation<'a> { Cut(JString<'a>, bool), Suggest(i64) }

fn run_operation(
    mut env: JNIEnv, input: JString,
    ranges: Ranges, muted: jboolean, callbacks: JObject, operation: Operation,
) -> jlongArray {
    let operation = catch_unwind(AssertUnwindSafe(|| -> jni::errors::Result<jlongArray> {
        let input: String = env.get_string(&input)?.into();
        let ranges = match ranges {
            Ranges::Single(start, end) => vec![(start, end)],
            Ranges::Multiple(array) => {
                let length = env.get_array_length(&array)?;
                if !(2..=512).contains(&length) || length % 2 != 0 {
                    env.throw_new("java/lang/IllegalArgumentException", "Expected 1 to 256 start/end pairs")?;
                    return Ok(ptr::null_mut());
                }
                let mut values = vec![0i64; length as usize];
                env.get_long_array_region(&array, 0, &mut values)?;
                values.chunks_exact(2).map(|pair| (pair[0], pair[1])).collect()
            },
        };
        let callback = Arc::new(JavaCallbacks {
            vm: env.get_java_vm()?, object: env.new_global_ref(callbacks)?,
            failure: Mutex::new(None), cancelled: AtomicBool::new(false),
        });
        let result = match operation {
            Operation::Cut(output, copy_only) => {
                let output: String = env.get_string(&output)?.into();
                let cut = if copy_only { cut_ranges_copy_video } else { cut_ranges };
                cut(&input, &output, &ranges, muted != 0, callback.clone()).map(|report|
                    vec![1, report.copied_video_packets as i64, report.encoded_video_packets as i64])
            },
            Operation::Suggest(source_end) => {
                suggest_copy_ranges(&input, &ranges, source_end, muted != 0, callback.clone()).map(|ranges|
                    std::iter::once(1).chain(ranges.into_iter().flat_map(|(start, end)| [start, end])).collect())
            },
        };
        let failure = callback.failure.lock().unwrap_or_else(|p| p.into_inner()).take();
        if let Some(failure) = failure {
            match failure {
                CallbackFailure::Java(reference) => {
                    let throwable: &JThrowable = reference.as_obj().into();
                    env.throw(throwable)?;
                },
                CallbackFailure::Native(reason) => env.throw_new("java/lang/IllegalStateException", reason)?,
            }
            return Ok(ptr::null_mut());
        }
        let values = match result {
            Ok(values) => values,
            Err(error) => {
                warn(&format!("Smart rendering declined: {error:#}"));
                if callback.cancelled.load(Ordering::Relaxed) { vec![-1, 0, 0] }
                else { vec![0, crate::decline::code(&error), 0] }
            }
        };
        let array = env.new_long_array(values.len() as i32)?;
        env.set_long_array_region(&array, 0, &values)?;
        Ok(array.into_raw())
    }));
    match operation {
        Ok(Ok(array)) => array,
        Ok(Err(error)) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            }
            ptr::null_mut()
        },
        Err(_) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/io/IOException", "Native smart rendering panicked");
            }
            ptr::null_mut()
        }
    }
}
