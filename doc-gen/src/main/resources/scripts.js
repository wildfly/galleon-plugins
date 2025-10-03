// Documentation page JavaScript functionality

/**
 * Toggle the visibility of collapsible content sections
 * @param {HTMLElement} element - The toggle link that was clicked
 * @param {Event} event - The click event
 */
function toggleCollapsible(element, event) {
    event.preventDefault();

    // Find the next sibling ul element
    const collapsible = element.parentElement.nextElementSibling;

    // Toggle the display
    if (collapsible.style.display === 'block') {
        collapsible.style.display = 'none';
        element.classList.remove('expanded');
    } else {
        collapsible.style.display = 'block';
        element.classList.add('expanded');
    }
}

/**
 * Initialize collapsible functionality for all toggle links on the page
 * This is used for resource pages with dynamically generated toggle links
 */
function initializeCollapsibleLinks() {
    document.querySelectorAll('.toggle-link').forEach(link => {
        link.addEventListener('click', function(event) {
            event.preventDefault();
            const target = this.nextElementSibling;
            if (target && target.classList.contains('collapsible')) {
                target.style.display = (target.style.display === 'none' || target.style.display === '') ? 'block' : 'none';

                // Update the expanded class for consistent styling
                if (target.style.display === 'block') {
                    this.classList.add('expanded');
                } else {
                    this.classList.remove('expanded');
                }
            }
        });
    });
}

/**
 * Copy the content of a code block to the clipboard
 * @param {string} elementId - The ID of the code element to copy
 */
function copyToClipboard(elementId) {
    const codeElement = document.getElementById(elementId);
    if (!codeElement) {
        console.error('Element with ID', elementId, 'not found');
        return;
    }

    // Get the text content and clean it up
    const text = codeElement.textContent.trim();

    // Use the modern clipboard API if available
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(() => {
            showCopyFeedback(elementId);
        }).catch(err => {
            console.error('Failed to copy text: ', err);
            fallbackCopyToClipboard(text, elementId);
        });
    } else {
        fallbackCopyToClipboard(text, elementId);
    }
}

/**
 * Fallback method for copying text to clipboard
 * @param {string} text - The text to copy
 * @param {string} elementId - The ID of the element for feedback
 */
function fallbackCopyToClipboard(text, elementId) {
    const textArea = document.createElement('textarea');
    textArea.value = text;
    textArea.style.position = 'fixed';
    textArea.style.left = '-999999px';
    textArea.style.top = '-999999px';
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();

    try {
        document.execCommand('copy');
        showCopyFeedback(elementId);
    } catch (err) {
        console.error('Fallback: Oops, unable to copy', err);
    }

    document.body.removeChild(textArea);
}

/**
 * Show visual feedback when content is copied
 * @param {string} elementId - The ID of the element that was copied
 */
function showCopyFeedback(elementId) {
    const codeElement = document.getElementById(elementId);
    const container = codeElement.closest('.code-block-container');
    const button = container.querySelector('.copy-btn');

    // Temporarily change button text/icon
    const originalText = button.textContent;
    button.textContent = '✓';
    button.style.color = '#28a745';

    setTimeout(() => {
        button.textContent = originalText;
        button.style.color = '';
    }, 1500);
}

/**
 * Switch between tabs in the tab interface
 * @param {Event} event - The click event
 * @param {string} tabId - The ID of the tab content to show
 */
function switchTab(event, tabId) {
    // Remove active class from all tab buttons and content
    const tabButtons = document.querySelectorAll('.tab-button');
    const tabContents = document.querySelectorAll('.tab-content');

    tabButtons.forEach(button => {
        button.classList.remove('active');
        button.setAttribute('aria-selected', 'false');
    });

    tabContents.forEach(content => {
        content.classList.remove('active');
    });

    // Add active class to clicked button and corresponding content
    event.target.classList.add('active');
    event.target.setAttribute('aria-selected', 'true');
    document.getElementById(tabId).classList.add('active');
}

// Initialize when the DOM is loaded
document.addEventListener('DOMContentLoaded', initializeCollapsibleLinks);