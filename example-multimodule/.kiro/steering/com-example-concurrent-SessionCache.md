<!-- VIBETAGS-START -->
# Amazon Kiro Steering: SessionCache

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: All mutations go through ConcurrentHashMap; never introduce a synchronized block on the cache map.
<!-- VIBETAGS-END -->
