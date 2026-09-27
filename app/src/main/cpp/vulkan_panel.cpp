#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

constexpr const char* kTag = "QuestManagerVulkan";

void logFailure(const char* step, VkResult result) {
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s failed: %d", step, result);
}

struct Renderer {
  ANativeWindow* window = nullptr;
  VkInstance instance = VK_NULL_HANDLE;
  VkSurfaceKHR surface = VK_NULL_HANDLE;
  VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkQueue queue = VK_NULL_HANDLE;
  uint32_t queueFamily = 0;
  VkSwapchainKHR swapchain = VK_NULL_HANDLE;
  std::vector<VkImage> images;
  VkFormat format = VK_FORMAT_UNDEFINED;
  VkExtent2D extent{};
  VkBuffer staging = VK_NULL_HANDLE;
  VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
  VkCommandPool commandPool = VK_NULL_HANDLE;
  VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
  VkSemaphore imageReady = VK_NULL_HANDLE;
  VkSemaphore renderReady = VK_NULL_HANDLE;
  VkFence frameDone = VK_NULL_HANDLE;

  ~Renderer() {
    if (device != VK_NULL_HANDLE) {
      vkDeviceWaitIdle(device);
      if (frameDone != VK_NULL_HANDLE) vkDestroyFence(device, frameDone, nullptr);
      if (renderReady != VK_NULL_HANDLE) vkDestroySemaphore(device, renderReady, nullptr);
      if (imageReady != VK_NULL_HANDLE) vkDestroySemaphore(device, imageReady, nullptr);
      if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, nullptr);
      if (staging != VK_NULL_HANDLE) vkDestroyBuffer(device, staging, nullptr);
      if (stagingMemory != VK_NULL_HANDLE) vkFreeMemory(device, stagingMemory, nullptr);
      if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, swapchain, nullptr);
      vkDestroyDevice(device, nullptr);
    }
    if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, nullptr);
    if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
    if (window != nullptr) ANativeWindow_release(window);
  }

  bool initialize(JNIEnv* env, jobject javaSurface) {
    window = ANativeWindow_fromSurface(env, javaSurface);
    if (window == nullptr) return false;

    VkApplicationInfo appInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    appInfo.pApplicationName = "Quest Manager";
    appInfo.apiVersion = VK_API_VERSION_1_0;
    const char* instanceExtensions[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                                        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    instanceInfo.pApplicationInfo = &appInfo;
    instanceInfo.enabledExtensionCount = 2;
    instanceInfo.ppEnabledExtensionNames = instanceExtensions;
    VkResult result = vkCreateInstance(&instanceInfo, nullptr, &instance);
    if (result != VK_SUCCESS) { logFailure("vkCreateInstance", result); return false; }

    VkAndroidSurfaceCreateInfoKHR surfaceInfo{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    surfaceInfo.window = window;
    result = vkCreateAndroidSurfaceKHR(instance, &surfaceInfo, nullptr, &surface);
    if (result != VK_SUCCESS) { logFailure("vkCreateAndroidSurfaceKHR", result); return false; }

    uint32_t deviceCount = 0;
    result = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (result != VK_SUCCESS || deviceCount == 0) { logFailure("vkEnumeratePhysicalDevices", result); return false; }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());
    bool foundQueue = false;
    for (auto candidate : devices) {
      uint32_t count = 0;
      vkGetPhysicalDeviceQueueFamilyProperties(candidate, &count, nullptr);
      std::vector<VkQueueFamilyProperties> families(count);
      vkGetPhysicalDeviceQueueFamilyProperties(candidate, &count, families.data());
      for (uint32_t index = 0; index < count; ++index) {
        VkBool32 present = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(candidate, index, surface, &present);
        if (present && (families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
          physicalDevice = candidate;
          queueFamily = index;
          foundQueue = true;
          break;
        }
      }
      if (foundQueue) break;
    }
    if (!foundQueue) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "No graphics/present queue");
      return false;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = 1;
    deviceInfo.ppEnabledExtensionNames = deviceExtensions;
    result = vkCreateDevice(physicalDevice, &deviceInfo, nullptr, &device);
    if (result != VK_SUCCESS) { logFailure("vkCreateDevice", result); return false; }
    vkGetDeviceQueue(device, queueFamily, 0, &queue);

    VkSurfaceCapabilitiesKHR capabilities{};
    result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, &capabilities);
    if (result != VK_SUCCESS) { logFailure("surface capabilities", result); return false; }
    if ((capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "Surface cannot receive Vulkan transfer");
      return false;
    }
    uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &formatCount, nullptr);
    if (formatCount == 0) return false;
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &formatCount, formats.data());
    VkSurfaceFormatKHR selected = formats[0];
    for (const auto& entry : formats) {
      if (entry.format == VK_FORMAT_R8G8B8A8_UNORM ||
          entry.format == VK_FORMAT_R8G8B8A8_SRGB) {
        selected = entry;
        break;
      }
      if (entry.format == VK_FORMAT_B8G8R8A8_UNORM ||
          entry.format == VK_FORMAT_B8G8R8A8_SRGB) selected = entry;
    }
    format = selected.format;
    const bool rgba = format == VK_FORMAT_R8G8B8A8_UNORM || format == VK_FORMAT_R8G8B8A8_SRGB;
    const bool bgra = format == VK_FORMAT_B8G8R8A8_UNORM || format == VK_FORMAT_B8G8R8A8_SRGB;
    if (!rgba && !bgra) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "Unsupported surface format: %d", format);
      return false;
    }

    if (capabilities.currentExtent.width != UINT32_MAX) {
      extent = capabilities.currentExtent;
    } else {
      extent.width = std::clamp(static_cast<uint32_t>(ANativeWindow_getWidth(window)),
                                capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
      extent.height = std::clamp(static_cast<uint32_t>(ANativeWindow_getHeight(window)),
                                 capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    }
    if (extent.width == 0 || extent.height == 0) return false;
    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0) imageCount = std::min(imageCount, capabilities.maxImageCount);
    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if ((capabilities.supportedCompositeAlpha & alpha) == 0) {
      for (auto choice : {VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                          VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
                          VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR}) {
        if (capabilities.supportedCompositeAlpha & choice) { alpha = choice; break; }
      }
    }
    VkSwapchainCreateInfoKHR swapInfo{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    swapInfo.surface = surface;
    swapInfo.minImageCount = imageCount;
    swapInfo.imageFormat = format;
    swapInfo.imageColorSpace = selected.colorSpace;
    swapInfo.imageExtent = extent;
    swapInfo.imageArrayLayers = 1;
    swapInfo.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    swapInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // The Android compositor rotates this 2D window. Our bitmap is drawn in window
    // coordinates, so request identity rather than claiming it is already rotated.
    swapInfo.preTransform = (capabilities.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                                ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                                : capabilities.currentTransform;
    swapInfo.compositeAlpha = alpha;
    swapInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    swapInfo.clipped = VK_TRUE;
    result = vkCreateSwapchainKHR(device, &swapInfo, nullptr, &swapchain);
    if (result != VK_SUCCESS) { logFailure("vkCreateSwapchainKHR", result); return false; }
    vkGetSwapchainImagesKHR(device, swapchain, &imageCount, nullptr);
    images.resize(imageCount);
    vkGetSwapchainImagesKHR(device, swapchain, &imageCount, images.data());

    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = static_cast<VkDeviceSize>(extent.width) * extent.height * 4;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    result = vkCreateBuffer(device, &bufferInfo, nullptr, &staging);
    if (result != VK_SUCCESS) { logFailure("vkCreateBuffer", result); return false; }
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(device, staging, &requirements);
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
    int memoryType = -1;
    for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
      VkMemoryPropertyFlags flags = memoryProperties.memoryTypes[index].propertyFlags;
      if ((requirements.memoryTypeBits & (1u << index)) &&
          (flags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
              (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
        memoryType = static_cast<int>(index);
        break;
      }
    }
    if (memoryType < 0) return false;
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = static_cast<uint32_t>(memoryType);
    result = vkAllocateMemory(device, &allocation, nullptr, &stagingMemory);
    if (result != VK_SUCCESS) { logFailure("vkAllocateMemory", result); return false; }
    vkBindBufferMemory(device, staging, stagingMemory, 0);

    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily;
    result = vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool);
    if (result != VK_SUCCESS) { logFailure("vkCreateCommandPool", result); return false; }
    VkCommandBufferAllocateInfo commands{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    commands.commandPool = commandPool;
    commands.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commands.commandBufferCount = 1;
    result = vkAllocateCommandBuffers(device, &commands, &commandBuffer);
    if (result != VK_SUCCESS) { logFailure("vkAllocateCommandBuffers", result); return false; }
    VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    if (vkCreateSemaphore(device, &semaphoreInfo, nullptr, &imageReady) != VK_SUCCESS) return false;
    if (vkCreateSemaphore(device, &semaphoreInfo, nullptr, &renderReady) != VK_SUCCESS) return false;
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vkCreateFence(device, &fenceInfo, nullptr, &frameDone) != VK_SUCCESS) return false;
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "Vulkan surface %ux%u format=%d images=%u transform=%u current=%u",
                        extent.width, extent.height, format, imageCount, swapInfo.preTransform,
                        capabilities.currentTransform);
    return true;
  }

  bool draw(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        bitmapInfo.width != extent.width || bitmapInfo.height != extent.height) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "Bitmap size/format does not match swapchain");
      return false;
    }
    VkResult result = vkWaitForFences(device, 1, &frameDone, VK_TRUE, UINT64_MAX);
    if (result != VK_SUCCESS) { logFailure("vkWaitForFences", result); return false; }
    void* source = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &source) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    void* destination = nullptr;
    result = vkMapMemory(device, stagingMemory, 0, VK_WHOLE_SIZE, 0, &destination);
    if (result != VK_SUCCESS) {
      AndroidBitmap_unlockPixels(env, bitmap);
      logFailure("vkMapMemory", result);
      return false;
    }
    const bool bgra = format == VK_FORMAT_B8G8R8A8_UNORM || format == VK_FORMAT_B8G8R8A8_SRGB;
    const auto* bytes = static_cast<const uint8_t*>(source);
    auto* output = static_cast<uint8_t*>(destination);
    for (uint32_t row = 0; row < extent.height; ++row) {
      const uint8_t* inputRow = bytes + row * bitmapInfo.stride;
      uint8_t* outputRow = output + static_cast<size_t>(row) * extent.width * 4;
      if (bgra) {
        for (uint32_t column = 0; column < extent.width; ++column) {
          const uint8_t* pixel = inputRow + column * 4;
          uint8_t* target = outputRow + column * 4;
          target[0] = pixel[2];
          target[1] = pixel[1];
          target[2] = pixel[0];
          target[3] = pixel[3];
        }
      } else {
        std::memcpy(outputRow, inputRow, static_cast<size_t>(extent.width) * 4);
      }
    }
    vkUnmapMemory(device, stagingMemory);
    AndroidBitmap_unlockPixels(env, bitmap);

    uint32_t imageIndex = 0;
    result = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, imageReady,
                                   VK_NULL_HANDLE, &imageIndex);
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
      logFailure("vkAcquireNextImageKHR", result);
      return false;
    }
    vkResetFences(device, 1, &frameDone);
    vkResetCommandBuffer(commandBuffer, 0);
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    result = vkBeginCommandBuffer(commandBuffer, &begin);
    if (result != VK_SUCCESS) { logFailure("vkBeginCommandBuffer", result); return false; }
    VkImageMemoryBarrier before{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    before.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    before.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    before.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    before.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    before.image = images[imageIndex];
    before.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    before.subresourceRange.levelCount = 1;
    before.subresourceRange.layerCount = 1;
    before.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &before);
    VkBufferImageCopy copy{};
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.layerCount = 1;
    copy.imageExtent = {extent.width, extent.height, 1};
    vkCmdCopyBufferToImage(commandBuffer, staging, images[imageIndex],
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    VkImageMemoryBarrier after = before;
    after.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    after.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    after.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    after.dstAccessMask = 0;
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &after);
    result = vkEndCommandBuffer(commandBuffer);
    if (result != VK_SUCCESS) { logFailure("vkEndCommandBuffer", result); return false; }
    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &imageReady;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commandBuffer;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &renderReady;
    result = vkQueueSubmit(queue, 1, &submit, frameDone);
    if (result != VK_SUCCESS) { logFailure("vkQueueSubmit", result); return false; }
    VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &renderReady;
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain;
    present.pImageIndices = &imageIndex;
    result = vkQueuePresentKHR(queue, &present);
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
      logFailure("vkQueuePresentKHR", result);
      return false;
    }
    // Frames arrive only on data/input changes. Waiting here lets the next frame
    // safely reuse the same binary semaphores after presentation has consumed them.
    result = vkQueueWaitIdle(queue);
    if (result != VK_SUCCESS) { logFailure("vkQueueWaitIdle", result); return false; }
    return true;
  }
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_adaptivecodex_quest_manager_VulkanPanelView_nativeCreate(
    JNIEnv* env, jobject, jobject surface) {
  auto* renderer = new Renderer();
  if (!renderer->initialize(env, surface)) {
    delete renderer;
    return 0;
  }
  return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adaptivecodex_quest_manager_VulkanPanelView_nativeRender(
    JNIEnv* env, jobject, jlong handle, jobject bitmap) {
  if (handle == 0) return JNI_FALSE;
  return reinterpret_cast<Renderer*>(handle)->draw(env, bitmap) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_adaptivecodex_quest_manager_VulkanPanelView_nativeWidth(
    JNIEnv*, jobject, jlong handle) {
  return handle == 0 ? 0 : static_cast<jint>(reinterpret_cast<Renderer*>(handle)->extent.width);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_adaptivecodex_quest_manager_VulkanPanelView_nativeHeight(
    JNIEnv*, jobject, jlong handle) {
  return handle == 0 ? 0 : static_cast<jint>(reinterpret_cast<Renderer*>(handle)->extent.height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_adaptivecodex_quest_manager_VulkanPanelView_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<Renderer*>(handle);
}
