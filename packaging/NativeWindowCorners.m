#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <objc/runtime.h>
#include <jni.h>

@interface CastCornerState : NSObject
@property(nonatomic, weak) NSWindow *window;
@property(nonatomic) CGFloat radius;
@property(nonatomic, strong) NSTimer *timer;
- (void)refresh;
@end
@implementation CastCornerState
- (void)refresh {
    NSWindow *window = self.window;
    if (!window) { [self.timer invalidate]; return; }
    NSView *clip = window.contentView.superview ?: window.contentView;
    clip.wantsLayer = YES;
    CALayer *layer = clip.layer;
    BOOL rounded = self.radius > 0;
    BOOL changed = layer.cornerRadius != self.radius || layer.masksToBounds != rounded
        || window.opaque == rounded
        || (rounded && window.backgroundColor.alphaComponent != 0);
    if (!changed) return;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    layer.cornerRadius = self.radius;
    layer.masksToBounds = rounded;
    [CATransaction commit];
    // AWT keeps its opaque drawing surface. Only Cocoa clips the final result.
    window.opaque = !rounded;
    window.backgroundColor = rounded ? NSColor.clearColor : NSColor.blackColor;
    window.hasShadow = !rounded;
    NSLog(@"CAST_NATIVE_CORNERS restored radius=%.1f window=%ld", self.radius, window.windowNumber);
}
- (void)dealloc { [_timer invalidate]; }
@end
static char cornerStateKey;

JNIEXPORT void JNICALL Java_NativeWindowCorners_setCorners
    (JNIEnv *env, jclass type, jstring title, jdouble radius) {
    const jchar *chars = (*env)->GetStringChars(env, title, NULL);
    if (!chars) return;
    NSString *name = [[NSString alloc] initWithCharacters:(const unichar *)chars
        length:(NSUInteger)(*env)->GetStringLength(env, title)];
    (*env)->ReleaseStringChars(env, title, chars);
    // Do not block the EDT waiting for AppKit.
    dispatch_async(dispatch_get_main_queue(), ^{
        for (NSWindow *window in NSApp.windows) {
            if (![window.title isEqualToString:name] || !window.visible) continue;
            CastCornerState *state = objc_getAssociatedObject(window, &cornerStateKey);
            if (!state) {
                state = [CastCornerState new];
                state.window = window;
                objc_setAssociatedObject(window, &cornerStateKey, state, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            }
            state.radius = MAX(0, radius);
            [state refresh];
            if (radius > 0 && !state.timer) {
                // AWT replaces the backing layer after live frames begin and on resize.
                // Check native state without repainting or touching Java transparency.
                __weak CastCornerState *weakState = state;
                state.timer = [NSTimer timerWithTimeInterval:0.2 repeats:YES block:^(NSTimer *timer) {
                    CastCornerState *current = weakState;
                    if (current) [current refresh]; else [timer invalidate];
                }];
                [NSRunLoop.mainRunLoop addTimer:state.timer forMode:NSRunLoopCommonModes];
            } else if (radius <= 0) {
                [state.timer invalidate];
                state.timer = nil;
                objc_setAssociatedObject(window, &cornerStateKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            }
            return;
        }
        NSLog(@"CAST_NATIVE_CORNERS window not found: %@", name);
    });
}
