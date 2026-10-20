// SPDX-License-Identifier: AGPL-3.0-or-later
// Algorithm port referencing Ultralytics 8.4.104 (Ultralytics AGPL-3.0).
import Foundation
import CoreGraphics

/// BoT-SORT's XYWH constant-velocity model. State: cx, cy, w, h, vx, vy, vw, vh.
/// Noise weights follow the server's pinned Ultralytics 8.4.104 KalmanFilterXYWH.
nonisolated struct PrivacyTrackKalman {
    private(set) var mean: [Double]
    private(set) var covariance: [Double]

    init(box: CGRect) {
        mean = [box.midX, box.midY, box.width, box.height, 0, 0, 0, 0]
        covariance = Array(repeating: 0, count: 64)
        for i in 0..<8 {
            let dimension = i % 2 == 0 ? box.width : box.height
            let deviation = dimension * (i < 4 ? 0.1 : 0.0625)
            covariance[i * 8 + i] = deviation * deviation
        }
    }

    var box: CGRect {
        CGRect(x: mean[0] - mean[2] / 2, y: mean[1] - mean[3] / 2,
               width: max(0, mean[2]), height: max(0, mean[3]))
    }

    mutating func predict(lost: Bool) {
        if lost { mean[6] = 0; mean[7] = 0 }
        let dimensions = [mean[2], mean[3]]
        var next = covariance
        for row in 0..<8 {
            for column in 0..<8 {
                var value = covariance[row * 8 + column]
                if row < 4 { value += covariance[(row + 4) * 8 + column] }
                if column < 4 { value += covariance[row * 8 + column + 4] }
                if row < 4 && column < 4 { value += covariance[(row + 4) * 8 + column + 4] }
                next[row * 8 + column] = value
            }
            let deviation = dimensions[row % 2] * (row < 4 ? 1.0 / 20 : 1.0 / 160)
            next[row * 8 + row] += deviation * deviation
        }
        for i in 0..<4 { mean[i] += mean[i + 4] }
        covariance = next
    }

    mutating func correct(box: CGRect) {
        let measurement = [box.midX, box.midY, box.width, box.height]
        var projected = [Double](repeating: 0, count: 16)
        for r in 0..<4 {
            for c in 0..<4 { projected[r * 4 + c] = covariance[r * 8 + c] }
            let noise = mean[2 + r % 2] / 20
            projected[r * 4 + r] += noise * noise
        }
        guard let inverse = Self.inverse4(projected) else {
            self = Self(box: box)
            return
        }
        var gain = [Double](repeating: 0, count: 32)
        for r in 0..<8 {
            for c in 0..<4 {
                for k in 0..<4 { gain[r * 4 + c] += covariance[r * 8 + k] * inverse[k * 4 + c] }
            }
        }
        let residual = (0..<4).map { measurement[$0] - mean[$0] }
        for r in 0..<8 {
            for k in 0..<4 { mean[r] += gain[r * 4 + k] * residual[k] }
        }
        var next = covariance
        for r in 0..<8 {
            for c in 0..<8 {
                for k in 0..<4 { next[r * 8 + c] -= gain[r * 4 + k] * covariance[k * 8 + c] }
            }
        }
        covariance = next
    }

    /// Apply the 2x2 camera warp to all four state pairs, including covariance.
    mutating func compensate(_ warp: CGAffineTransform) {
        let rotation = [[Double(warp.a), Double(warp.c)], [Double(warp.b), Double(warp.d)]]
        let oldMean = mean
        let oldCovariance = covariance
        for pair in 0..<4 {
            for r in 0..<2 {
                mean[pair * 2 + r] = rotation[r][0] * oldMean[pair * 2] + rotation[r][1] * oldMean[pair * 2 + 1]
            }
        }
        mean[0] += warp.tx; mean[1] += warp.ty
        for r in 0..<8 {
            for c in 0..<8 {
                var value = 0.0
                for i in 0..<2 {
                    for j in 0..<2 {
                        value += rotation[r % 2][i] * oldCovariance[(r / 2 * 2 + i) * 8 + c / 2 * 2 + j] * rotation[c % 2][j]
                    }
                }
                covariance[r * 8 + c] = value
            }
        }
    }

    private static func inverse4(_ matrix: [Double]) -> [Double]? {
        var augmented = [Double](repeating: 0, count: 32)
        for r in 0..<4 {
            for c in 0..<4 { augmented[r * 8 + c] = matrix[r * 4 + c] }
            augmented[r * 8 + 4 + r] = 1
        }
        for column in 0..<4 {
            let pivot = (column..<4).max { abs(augmented[$0 * 8 + column]) < abs(augmented[$1 * 8 + column]) }!
            guard abs(augmented[pivot * 8 + column]) > 1e-12 else { return nil }
            if pivot != column {
                for c in 0..<8 { augmented.swapAt(pivot * 8 + c, column * 8 + c) }
            }
            let divisor = augmented[column * 8 + column]
            for c in 0..<8 { augmented[column * 8 + c] /= divisor }
            for r in 0..<4 where r != column {
                let factor = augmented[r * 8 + column]
                for c in 0..<8 { augmented[r * 8 + c] -= factor * augmented[column * 8 + c] }
            }
        }
        return (0..<16).map { augmented[($0 / 4) * 8 + 4 + $0 % 4] }
    }
}

nonisolated enum PrivacyTrackAssignment {
    /// Rectangular Hungarian assignment with unmatched vertices. A real match has
    /// cost (distance - limit); dummy columns cost zero, matching LAPJV's cost limit.
    static func solve(_ costs: [[Double]], limit: Double) -> [(Int, Int)] {
        guard let first = costs.first, !first.isEmpty else { return [] }
        let transpose = costs.count > first.count
        let rows = transpose ? first.count : costs.count
        let realColumns = transpose ? costs.count : first.count
        let columns = realColumns + rows
        var u = [Double](repeating: 0, count: rows + 1)
        var v = [Double](repeating: 0, count: columns + 1)
        var p = [Int](repeating: 0, count: columns + 1)
        var way = p
        for row in 1...rows {
            p[0] = row
            var column = 0
            var minValue = [Double](repeating: .infinity, count: columns + 1)
            var used = [Bool](repeating: false, count: columns + 1)
            repeat {
                used[column] = true
                let activeRow = p[column]
                var delta = Double.infinity
                var next = 0
                for candidate in 1...columns where !used[candidate] {
                    let cost: Double
                    if candidate <= realColumns {
                        cost = (transpose ? costs[candidate - 1][activeRow - 1] : costs[activeRow - 1][candidate - 1]) - limit
                    } else { cost = 0 }
                    let reduced = cost - u[activeRow] - v[candidate]
                    if reduced < minValue[candidate] { minValue[candidate] = reduced; way[candidate] = column }
                    if minValue[candidate] < delta { delta = minValue[candidate]; next = candidate }
                }
                for candidate in 0...columns {
                    if used[candidate] { u[p[candidate]] += delta; v[candidate] -= delta }
                    else { minValue[candidate] -= delta }
                }
                column = next
            } while p[column] != 0
            repeat {
                let prior = way[column]
                p[column] = p[prior]
                column = prior
            } while column != 0
        }
        return (1...realColumns).compactMap { column in
            guard p[column] != 0 else { return nil }
            let pair = transpose ? (column - 1, p[column] - 1) : (p[column] - 1, column - 1)
            return costs[pair.0][pair.1] <= limit ? pair : nil
        }.sorted { $0.0 < $1.0 }
    }
}
