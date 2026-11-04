#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN
/// 한 큐에서만 쓰는 OpenCV sparseOptFlow GMC다. 설정은 innolive-ai 서버와 맞춘다.
@interface PrivacySparseFlow : NSObject
- (void)reset;
/// 입력은 좌상단 원점 BGRA다. 반환하는 변환의 이동량은 입력 이미지 픽셀 단위다.
- (CGAffineTransform)estimateBGRA:(NSData *)pixels
                          width:(NSInteger)width
                         height:(NSInteger)height
                    bytesPerRow:(NSInteger)bytesPerRow;
@end
NS_ASSUME_NONNULL_END
