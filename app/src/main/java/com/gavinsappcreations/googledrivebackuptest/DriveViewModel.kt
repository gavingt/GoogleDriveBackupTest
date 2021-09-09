package com.gavinsappcreations.googledrivebackuptest

import android.view.SearchEvent
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class DriveViewModel: ViewModel() {

    // All possible events for the Fragment.
/*    sealed class DriveEvent {
        object NavigateToAddressDetailsFragmentEvent : SearchEvent.ExploreEvent()
        class DeliveryAddressChangedEvent (val newAddress: String) : SearchEvent.ExploreEvent()
        class NavigateToCategoryEvent(val category: SnackCategories) : SearchEvent.ExploreEvent()
        class DisplayErrorToast(val errorText: String) : SearchEvent.ExploreEvent()
    }*/

    // Holds current state of the Fragment.
    data class DriveState(
        var deliveryAddress: String? = null)


    // Channel for sending one-off events from viewModel to Fragment.
    private val _eventChannel = Channel<SearchEvent>(Channel.UNLIMITED)

    // Turn eventChannel into a Flow that the Fragment can use to receive events.
    val eventFlow = _eventChannel.receiveAsFlow()

    // Private mutable Flow representing the state of the Fragment.
    private val _viewState = MutableStateFlow(DriveState())

    // Public non-mutable Flow representing the state of the Fragment.
    val viewState = _viewState.asStateFlow()
}