#!/bin/bash

# Unzip archives
unzip -o -qq "./arm64-iphoneos.zip" -d "./arm64-iphoneos"
unzip -o -qq "./arm64-iphonesimulator.zip" -d "./arm64-iphonesimulator"
unzip -o -qq "./x86_64-iphonesimulator.zip" -d "./x86_64-iphonesimulator"

# Set module directory
ARM_DIR="./arm64-iphonesimulator/"
x86_DIR="path/to/modules"

# Loop through each module in the directory
for module in "$MODULE_DIR"/*; do
  # Extract the module name without the architecture suffix
  module_name=$(basename "$module" | sed 's/_x86_64//;s/_arm64//')

  # Define the paths for x86_64 and arm64 versions of the module
  x86_64_module="$MODULE_DIR/${module_name}_x86_64/module.dylib"
  arm64_module="$MODULE_DIR/${module_name}_arm64/module.dylib"

  # Define the output path for the combined module
  output_module="$MODULE_DIR/${module_name}/module.dylib"

  # Create the combined module using lipo
  lipo -create -output "$output_module" "$x86_64_module" "$arm64_module"
done
