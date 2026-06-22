# frozen_string_literal: true

source "https://rubygems.org"

# Chirpy theme as a gem. Pin a major version so updates don't surprise you.
gem "jekyll-theme-chirpy", "~> 7.0"

# Tooling Chirpy expects.
group :test do
  gem "html-proofer", "~> 5.0"
end

# Windows/JRuby helpers — harmless on macOS, useful if you ever switch machines.
platforms :mingw, :x64_mingw, :mswin, :jruby do
  gem "tzinfo", ">= 1", "< 3"
  gem "tzinfo-data"
end

gem "wdm", "~> 0.1.1", :platforms => [:mingw, :x64_mingw, :mswin]
