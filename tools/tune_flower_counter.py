"""
Flower Counter
Himanshu S Mazumdar
Dharmsinh Desai University, Nadiad, INDIA

Desktop mirror of the Android FlowerCounter pipeline, used to pick sensible
default slider values from a sample photo before trying them on a phone.

Usage:
    pip install opencv-python numpy
    python tune_flower_counter.py path/to/photo.jpg            # parameter sweep
    python tune_flower_counter.py path/to/photo.jpg --render   # writes *_result.jpg / *_mask.png

Keep this in sync with app/src/main/java/com/flowercounter/FlowerCounter.kt.
"""
import argparse
import os

import cv2

WORKING_LONG_SIDE = 1280


def thresholds(sensitivity):
    """Slider 0..100 -> (minimum brightness V, maximum saturation S)."""
    return int(240 - 0.8 * sensitivity), int(20 + 0.7 * sensitivity)


def ellipse(k):
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))


def count(img, sensitivity=60, min_area=60, blur=1, open_k=None, close_k=5,
          circ=0.5, conv=0.7, inert=0.3, max_area=5000):
    if open_k is None:
        open_k = open_kernel_for(min_area)
    scale = WORKING_LONG_SIDE / max(img.shape[:2])
    if scale < 1:
        img = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)

    v_min, s_max = thresholds(sensitivity)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    mask = cv2.inRange(hsv, (0, 0, v_min), (179, s_max, 255))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, ellipse(close_k))
    if blur > 0:
        k = blur * 2 + 1
        mask = cv2.GaussianBlur(mask, (k, k), 0)
        _, mask = cv2.threshold(mask, 127, 255, cv2.THRESH_BINARY)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, ellipse(open_k))

    # Same as the app: the Android Java API can't set blobColor, so detect dark blobs
    # on an inverted mask.
    p = cv2.SimpleBlobDetector_Params()
    p.minThreshold, p.maxThreshold, p.thresholdStep = 127, 128, 1
    p.minRepeatability = 1
    p.minDistBetweenBlobs = 2
    p.filterByColor = True
    p.filterByArea, p.minArea, p.maxArea = True, min_area, max_area
    p.filterByCircularity, p.minCircularity = True, circ
    p.filterByConvexity, p.minConvexity = True, conv
    p.filterByInertia, p.minInertiaRatio = True, inert
    keypoints = cv2.SimpleBlobDetector_create(p).detect(cv2.bitwise_not(mask))
    return img, mask, keypoints


def open_kernel_for(min_area):
    k = min(max(int(min_area ** 0.5), 3), 15)
    return k - 1 if k % 2 == 0 else k


def estimate_clusters(mask, keypoints, min_area):
    """Large blobs with no keypoint are usually touching flowers; estimate them by area."""
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    if n <= 1 or not keypoints:
        return 0, []
    h, w = labels.shape
    hit = set()
    for kp in keypoints:
        x = min(max(int(kp.pt[0]), 0), w - 1)
        y = min(max(int(kp.pt[1]), 0), h - 1)
        hit.add(int(labels[y, x]))
    hit.discard(0)
    single_areas = sorted(int(stats[i, cv2.CC_STAT_AREA]) for i in hit)
    if not single_areas:
        return 0, []
    # Small detections are often partially hidden flowers, so the 90th percentile
    # is a better stand-in for "one whole flower" than the median.
    flower_area = max(single_areas[int(len(single_areas) * 0.9)], min_area)
    flowers_in = {}
    for label in range(1, n):
        if label in hit:
            continue
        k = int(round(stats[label, cv2.CC_STAT_AREA] / flower_area))
        if k >= 2:
            flowers_in[label] = k
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    clusters = [c for c in contours if int(labels[c[0][0][1], c[0][0][0]]) in flowers_in]
    return sum(flowers_in.values()), clusters


def render(img, keypoints, clusters=(), extra=0):
    out = img.copy()
    cv2.drawContours(out, list(clusters), -1, (0, 165, 255), 2)
    for kp in keypoints:
        r = max(int(kp.size / 2) + 4, 8)
        cv2.circle(out, (int(kp.pt[0]), int(kp.pt[1])), r, (0, 0, 255), 2)
    cv2.putText(out, f"Count: {len(keypoints) + extra}", (20, 50),
                cv2.FONT_HERSHEY_SIMPLEX, 1.5, (0, 0, 255), 3)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--render", action="store_true")
    ap.add_argument("--sensitivity", type=int, default=60)
    ap.add_argument("--min-area", type=int, default=60)
    ap.add_argument("--blur", type=int, default=1)
    ap.add_argument("--open", type=int, default=None, help="default: derived from --min-area like the app")
    args = ap.parse_args()

    img = cv2.imread(args.image)
    if img is None:
        raise SystemExit(f"Cannot read {args.image}")

    if args.render:
        small, mask, kps = count(img, args.sensitivity, args.min_area, args.blur, args.open)
        extra, clusters = estimate_clusters(mask, kps, args.min_area)
        base = os.path.splitext(args.image)[0]
        cv2.imwrite(base + "_result.jpg", render(small, kps, clusters, extra))
        cv2.imwrite(base + "_mask.png", mask)
        print(f"blobs={len(kps)} clusterExtra={extra} total={len(kps) + extra} -> {base}_result.jpg")
        return

    for open_k in (5, 7, 9):
        for sens in (40, 50, 60, 70):
            row = []
            for a, b in ((40, 1), (60, 0), (60, 1), (100, 1)):
                _, mask, kps = count(img, sens, a, b, open_k)
                extra, _ = estimate_clusters(mask, kps, a)
                row.append(f"{len(kps)}+{extra}")
            print(f"open {open_k} sens {sens} (minArea,blur) 40/1 60/0 60/1 100/1 -> blobs+clusters {row}")


if __name__ == "__main__":
    main()
