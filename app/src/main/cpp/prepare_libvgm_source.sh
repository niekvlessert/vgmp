#!/bin/bash
# Prepare libvgm source by applying patches
# This script should be run before building the project

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIBVGM_DIR="$SCRIPT_DIR/libvgm"
PATCHES_DIR="$SCRIPT_DIR/patches"

# Check if libvgm exists
if [ ! -d "$LIBVGM_DIR" ]; then
    echo "Error: libvgm directory not found at $LIBVGM_DIR"
    exit 1
fi

# Check if patches directory exists
if [ ! -d "$PATCHES_DIR" ]; then
    echo "No patches directory found, skipping patch application"
    exit 0
fi

# Apply each patch file
for patch_file in "$PATCHES_DIR"/*.patch; do
    if [ -f "$patch_file" ]; then
        patch_name=$(basename "$patch_file")
        echo "Applying patch: $patch_name"
        
        # Check if patch is already applied
        if cd "$LIBVGM_DIR" && git apply --check "$patch_file" 2>/dev/null; then
            cd "$LIBVGM_DIR" && git apply "$patch_file"
            echo "  Applied successfully"
        else
            echo "  Patch already applied or failed to apply (skipping)"
        fi
    fi
done

echo "libvgm source preparation complete"
