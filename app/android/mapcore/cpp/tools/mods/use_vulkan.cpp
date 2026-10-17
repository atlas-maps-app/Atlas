// `import vulkan`: Vulkan-Hpp's named module (which imports std itself), with the core's configuration.
import vulkan;
import std;

int main() {
    const vk::ApplicationInfo app{.pApplicationName = "mods", .apiVersion = vk::ApiVersion11};
    std::println("import vulkan: API {}.{}", vk::apiVersionMajor(app.apiVersion), vk::apiVersionMinor(app.apiVersion));
    return 0;
}
