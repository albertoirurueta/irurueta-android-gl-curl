// Jump to a page without animation. Indices are zero based, and the current index is the page
// shown on the right side.
curlView.setCurrentIndex(0)

// Animate a single page flip that lands on page 5: the flipping page reveals page 5
// even if it is not adjacent to the current one
curlView.pageJumpDurationTime = 500L
curlView.setSmoothCurrentIndex(5)

// Read the current page
val page = curlView.currentIndex
