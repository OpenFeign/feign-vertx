/**
 * Copyright 2018 Comcast Cable Communications Management, LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign;

import java.lang.annotation.*;
import java.lang.annotation.Target;

/**
 * This annotation is used to designate the fallback class for
 * VertxFeign when using {@link io.vertx.circuitbreaker.CircuitBreaker}
 * All methods in the interface should be implemented in the
 * designated class.
 * Example:
 *    {@code @Fallback( IcecreamServiceApiFallback.class )}
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Fallback {
  /**
   * @see Fallback
   */
  Class<?> value() default void.class;
}
