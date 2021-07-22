package com.github.k1rakishou.chan.core.manager

import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CurrentOpenedDescriptorStateManager {
  private val _currentCatalogDescriptorFlow = MutableStateFlow<ChanDescriptor.CatalogDescriptor?>(null)
  val currentCatalogDescriptorFlow: StateFlow<ChanDescriptor.CatalogDescriptor?>
    get() = _currentCatalogDescriptorFlow.asStateFlow()
  val currentCatalogDescriptor: ChanDescriptor.CatalogDescriptor?
    get() = currentCatalogDescriptorFlow.value

  private val _currentThreadDescriptorFlow = MutableStateFlow<ChanDescriptor.ThreadDescriptor?>(null)
  val currentThreadDescriptorFlow: StateFlow<ChanDescriptor.ThreadDescriptor?>
    get() = _currentThreadDescriptorFlow.asStateFlow()
  val currentThreadDescriptor: ChanDescriptor.ThreadDescriptor?
    get() = currentThreadDescriptorFlow.value

  fun updateCatalogDescriptor(catalogDescriptor: ChanDescriptor.CatalogDescriptor?) {
    _currentCatalogDescriptorFlow.value = catalogDescriptor
  }

  fun updateThreadDescriptor(threadDescriptor: ChanDescriptor.ThreadDescriptor?) {
    _currentThreadDescriptorFlow.value = threadDescriptor
  }

}