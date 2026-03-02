#!/bin/bash
# Prepare libkss source by applying patches
# This script should be run before building the project

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIBKSS_DIR="$SCRIPT_DIR/libkss"
PATCHES_DIR="$SCRIPT_DIR/patches"

# Check if libkss exists
if [ ! -d "$LIBKSS_DIR" ]; then
    echo "Error: libkss directory not found at $LIBKSS_DIR"
    exit 1
fi

# Check if patches directory exists
if [ ! -d "$PATCHES_DIR" ]; then
    echo "No patches directory found, skipping patch application"
    exit 0
fi

# Apply each libkss-specific patch file
for patch_file in "$PATCHES_DIR"/libkss-*.patch; do
    if [ -f "$patch_file" ]; then
        patch_name=$(basename "$patch_file")
        echo "Applying patch: $patch_name"
        
        # Check if patch is already applied
        if cd "$LIBKSS_DIR" && git apply --check "$patch_file" 2>/dev/null; then
            cd "$LIBKSS_DIR" && git apply "$patch_file"
            echo "  Applied successfully"
        else
            echo "  Patch already applied or failed to apply (skipping)"
        fi
    fi
done

echo "libkss source preparation complete"
