export default {
  extends: ["@commitlint/config-conventional"],
  rules: {
    "scope-enum": [
      2,
      "always",
      ["android", "desktop", "ble", "wifi", "docs", "ci", "deps", "release"],
    ],
    "header-max-length": [2, "always", 100],
    "subject-case": [2, "never", ["start-case", "pascal-case", "upper-case"]],
  },
};
