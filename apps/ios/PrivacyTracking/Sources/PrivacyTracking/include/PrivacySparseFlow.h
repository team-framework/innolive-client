#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN
/// Queue-confined OpenCV sparseOptFlow GMC, matching innolive-ai's server settings.
@interface PrivacySparseFlow : NSObject
- (void)reset;
/// Top-left-origin BGRA. Translation in the returned transform uses input-image pixels.
- (CGAffineTransform)estimateBGRA:(NSData *)pixels
                          width:(NSInteger)width
                         height:(NSInteger)height
                    bytesPerRow:(NSInteger)bytesPerRow;
@end
NS_ASSUME_NONNULL_END
