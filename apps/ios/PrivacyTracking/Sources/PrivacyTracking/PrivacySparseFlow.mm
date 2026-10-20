// SPDX-License-Identifier: AGPL-3.0-or-later
// Algorithm port referencing Ultralytics 8.4.104 (Ultralytics AGPL-3.0).
#import "PrivacySparseFlow.h"
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>
#include <opencv2/calib3d.hpp>

@implementation PrivacySparseFlow {
    cv::Mat _previous;
    std::vector<cv::Point2f> _points;
    int _downscale;
    cv::Size _inputSize;
}
- (void)reset {
    _previous.release();
    _points.clear();
    _downscale = 0;
    _inputSize = cv::Size();
}
- (CGAffineTransform)estimateBGRA:(NSData *)pixels width:(NSInteger)width
                         height:(NSInteger)height bytesPerRow:(NSInteger)bytesPerRow {
    if (width <= 0 || height <= 0 || bytesPerRow < width * 4 ||
        pixels.length < static_cast<NSUInteger>(bytesPerRow * height)) {
        [self reset];
        return CGAffineTransformIdentity;
    }
    try {
        const cv::Size size(static_cast<int>(width), static_cast<int>(height));
        if (size != _inputSize) { [self reset]; _inputSize = size; }
        if (_downscale == 0) { _downscale = std::max(2, (std::max(size.width, size.height) + 639) / 640); }
        cv::Mat input(size, CV_8UC4, const_cast<void *>(pixels.bytes), static_cast<size_t>(bytesPerRow));
        cv::Mat gray, frame;
        cv::cvtColor(input, gray, cv::COLOR_BGRA2GRAY);
        cv::resize(gray, frame, cv::Size(size.width / _downscale, size.height / _downscale));
        std::vector<cv::Point2f> points;
        cv::goodFeaturesToTrack(frame, points, 1000, 0.01, 1, cv::noArray(), 3, false, 0.04);
        CGAffineTransform result = CGAffineTransformIdentity;
        if (!_previous.empty() && !_points.empty()) {
            std::vector<cv::Point2f> matched;
            std::vector<uchar> status;
            std::vector<float> errors;
            cv::calcOpticalFlowPyrLK(_previous, frame, _points, matched, status, errors);
            std::vector<cv::Point2f> from, to;
            for (size_t i = 0; i < status.size(); ++i) {
                if (status[i]) { from.push_back(_points[i]); to.push_back(matched[i]); }
            }
            if (from.size() > 4) {
                cv::Mat inliers;
                cv::Mat warp = cv::estimateAffinePartial2D(from, to, inliers, cv::RANSAC);
                if (!warp.empty() && cv::checkRange(warp)) {
                    result = CGAffineTransformMake(warp.at<double>(0, 0), warp.at<double>(1, 0),
                                                   warp.at<double>(0, 1), warp.at<double>(1, 1),
                                                   warp.at<double>(0, 2) * _downscale,
                                                   warp.at<double>(1, 2) * _downscale);
                }
            }
        }
        _previous = frame;
        _points = std::move(points);
        return result;
    } catch (const cv::Exception &) {
        // Same identity fallback as the server; don't bridge a C++ exception into Swift.
        [self reset];
        return CGAffineTransformIdentity;
    }
}
@end
